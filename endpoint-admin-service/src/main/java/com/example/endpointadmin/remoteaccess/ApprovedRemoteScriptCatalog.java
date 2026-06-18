package com.example.endpointadmin.remoteaccess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Faz 22.6.2 - the server-owned approved script library. Operators may reference a script id/version/hash
 * from this catalog; they may not paste script text or override the command line. The first implementation
 * deliberately maps approved diagnostics to the existing constrained-PTY broker path so it cannot become a
 * generic shell tunnel before the agent-side executor contract lands.
 */
public final class ApprovedRemoteScriptCatalog {

    private static final Pattern SCRIPT_ID = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9]+(\\.[0-9]+){0,2}$");
    private static final Pattern ARG_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._:-]{1,253}$");
    private static final Pattern INTEGER = Pattern.compile("^\\d{1,18}$");
    private static final Pattern TENANT =
            Pattern.compile("^(\\*|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");
    private static final SecretRedactor SECRET_SCANNER = new SecretRedactor(
            SecretRedactor.Profile.CREDENTIAL_ONLY, SecretRedactor.LabelMode.OPAQUE);
    private static final PtyCommandGuard COMMAND_GUARD =
            new PtyCommandGuard(PtyCommandGuard.PILOT_DEFAULT_ALLOWLIST);
    private static final PtyArgumentPolicy ARGUMENT_POLICY = PtyArgumentPolicy.PILOT_DEFAULT_POLICY;

    public enum ArgumentType { STRING, HOST, INTEGER, ENUM }

    public enum ResolutionStatus {
        ALLOWED,
        UNKNOWN,
        VERSION_MISMATCH,
        HASH_MISMATCH,
        DISABLED,
        REVOKED,
        TENANT_DENIED,
        RAW_SCRIPT_TEXT_DENIED,
        ARG_SCHEMA_INVALID,
        ARG_SECRET_MATERIAL,
        APPROVAL_EXPIRED,
        COMMAND_POLICY_DENIED
    }

    public record ArgumentSpec(String name,
                               ArgumentType type,
                               boolean required,
                               String defaultValue,
                               Set<String> enumValues,
                               int maxLength) {
        public ArgumentSpec {
            if (name == null || !ARG_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("argument name must be lower snake/camel id");
            }
            Objects.requireNonNull(type, "type");
            if (maxLength <= 0 || maxLength > 256) {
                throw new IllegalArgumentException("argument maxLength must be 1..256");
            }
            enumValues = enumValues == null ? Set.of() : enumValues.stream()
                    .map(value -> value == null ? "" : value.strip().toLowerCase(Locale.ROOT))
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
            if (type == ArgumentType.ENUM && enumValues.isEmpty()) {
                throw new IllegalArgumentException("enum arguments require enum values");
            }
            defaultValue = defaultValue == null || defaultValue.isBlank() ? null : defaultValue.strip();
            if (defaultValue != null && !accepts(type, enumValues, maxLength, defaultValue)) {
                throw new IllegalArgumentException("argument defaultValue is outside schema");
            }
        }

        boolean accepts(String value) {
            return accepts(type, enumValues, maxLength, value);
        }

        private static boolean accepts(ArgumentType type, Set<String> enumValues, int maxLength, String value) {
            if (value == null || value.isBlank() || value.length() > maxLength) {
                return false;
            }
            return switch (type) {
                case STRING -> value.chars().allMatch(ch ->
                        Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-' || ch == ':');
                case HOST -> HOST.matcher(value).matches();
                case INTEGER -> {
                    if (!INTEGER.matcher(value).matches()) {
                        yield false;
                    }
                    try {
                        Long.parseUnsignedLong(value);
                        yield true;
                    } catch (NumberFormatException e) {
                        yield false;
                    }
                }
                case ENUM -> enumValues.contains(value.toLowerCase(Locale.ROOT));
            };
        }
    }

    public record Definition(String scriptId,
                             String version,
                             String displayName,
                             String scriptBody,
                             String scriptBodySha256,
                             String signer,
                             String approver,
                             String approvalId,
                             long approvedAtEpochMillis,
                             long approvalExpiresAtEpochMillis,
                             Set<String> tenantIds,
                             RemoteOperationCatalog.RiskLevel riskLevel,
                             Set<RemoteOperationCatalog.ApprovalRequirement> approvalRequirements,
                             RemoteSessionCapability requiredCapability,
                             long timeoutMillis,
                             RemoteOperationCatalog.OutputRetention outputRetention,
                             RemoteOperationCatalog.RedactionClass redactionClass,
                             String cleanupNote,
                             String commandTemplate,
                             List<ArgumentSpec> argsSchema,
                             boolean enabled,
                             boolean revoked,
                             String disabledReason) {
        public Definition {
            if (scriptId == null || !SCRIPT_ID.matcher(scriptId).matches()) {
                throw new IllegalArgumentException("scriptId must be an uppercase approved-script id");
            }
            if (version == null || !VERSION.matcher(version).matches()) {
                throw new IllegalArgumentException("version must be immutable numeric version");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("displayName is required");
            }
            if (scriptBody == null || scriptBody.isBlank()) {
                throw new IllegalArgumentException("scriptBody is required");
            }
            String expectedHash = sha256Hex(scriptBody);
            scriptBodySha256 = canonicalHash(scriptBodySha256);
            if (!expectedHash.equals(scriptBodySha256)) {
                throw new IllegalArgumentException("scriptBodySha256 must match scriptBody");
            }
            if (SECRET_SCANNER.redactText(scriptBody).total() > 0) {
                throw new IllegalArgumentException("scriptBody must not contain credential or key material");
            }
            if (signer == null || signer.isBlank()) {
                throw new IllegalArgumentException("signer is required");
            }
            if (approver == null || approver.isBlank()) {
                throw new IllegalArgumentException("approver is required");
            }
            signer = signer.strip();
            approver = approver.strip();
            if (signer.equalsIgnoreCase(approver)) {
                throw new IllegalArgumentException("approved scripts require separate signer and approver");
            }
            if (approvalId == null || approvalId.isBlank()) {
                throw new IllegalArgumentException("approvalId is required");
            }
            approvalId = approvalId.strip();
            if (approvalExpiresAtEpochMillis <= approvedAtEpochMillis) {
                throw new IllegalArgumentException("approval expiry must be after approval time");
            }
            tenantIds = canonicalTenantIds(tenantIds);
            Objects.requireNonNull(riskLevel, "riskLevel");
            approvalRequirements = approvalRequirements == null ? Set.of()
                    : Set.copyOf(approvalRequirements);
            if (approvalRequirements.isEmpty()) {
                throw new IllegalArgumentException("at least one approval requirement is required");
            }
            Objects.requireNonNull(requiredCapability, "requiredCapability");
            if (riskLevel == RemoteOperationCatalog.RiskLevel.HIGH
                    || requiredCapability == RemoteSessionCapability.ELEVATION) {
                if (!approvalRequirements.contains(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP)
                        || !approvalRequirements.contains(RemoteOperationCatalog.ApprovalRequirement.DUAL_CONTROL)) {
                    throw new IllegalArgumentException("write/elevated scripts require WebAuthn and dual control");
                }
            }
            if (timeoutMillis <= 0 || timeoutMillis > 600_000L) {
                throw new IllegalArgumentException("timeoutMillis must be 1..600000");
            }
            Objects.requireNonNull(outputRetention, "outputRetention");
            Objects.requireNonNull(redactionClass, "redactionClass");
            if (cleanupNote == null || cleanupNote.isBlank()) {
                throw new IllegalArgumentException("cleanupNote is required");
            }
            cleanupNote = cleanupNote.strip();
            if (commandTemplate == null || commandTemplate.isBlank()) {
                throw new IllegalArgumentException("commandTemplate is required");
            }
            commandTemplate = commandTemplate.strip();
            argsSchema = argsSchema == null ? List.of() : List.copyOf(argsSchema);
            Set<String> names = new HashSet<>();
            for (ArgumentSpec spec : argsSchema) {
                if (!names.add(spec.name())) {
                    throw new IllegalArgumentException("duplicate argument name: " + spec.name());
                }
            }
            if (!enabled && (disabledReason == null || disabledReason.isBlank())) {
                throw new IllegalArgumentException("disabled entries require a reason");
            }
            disabledReason = disabledReason == null || disabledReason.isBlank()
                    ? null : disabledReason.strip().toLowerCase(Locale.ROOT);
        }
    }

    public record Invocation(String scriptId,
                             String scriptVersion,
                             String scriptHash,
                             Map<String, String> args,
                             String rawScriptText,
                             String scriptText,
                             String scriptBody,
                             String commandLine) {
        public Invocation {
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }

    public record PreparedScript(Definition definition, String commandLine, Map<String, String> args) {
        public PreparedScript {
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }

    public record Resolution(ResolutionStatus status, Definition definition, PreparedScript prepared, String reason) {
        public boolean allowed() {
            return status == ResolutionStatus.ALLOWED;
        }
    }

    private final Map<String, Definition> definitions;

    public ApprovedRemoteScriptCatalog(Set<Definition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("at least one approved script definition is required");
        }
        this.definitions = definitions.stream().collect(Collectors.toUnmodifiableMap(
                ApprovedRemoteScriptCatalog::key, Function.identity()));
    }

    public List<Definition> definitions() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(Definition::scriptId).thenComparing(Definition::version))
                .toList();
    }

    public Optional<Definition> find(String scriptId, String version) {
        if (scriptId == null || scriptId.isBlank() || version == null || version.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(key(scriptId.strip().toUpperCase(Locale.ROOT), version.strip())));
    }

    public Resolution resolve(String tenantId, Invocation invocation, long nowEpochMillis) {
        if (invocation == null) {
            return rejected(ResolutionStatus.UNKNOWN, null, "approved-script-unknown");
        }
        if (hasRawScriptText(invocation)) {
            return rejected(ResolutionStatus.RAW_SCRIPT_TEXT_DENIED, null, "approved-script-raw-text-denied");
        }
        Optional<Definition> maybe = find(invocation.scriptId(), invocation.scriptVersion());
        if (maybe.isEmpty()) {
            Optional<Definition> sameScript = findAnyVersion(invocation.scriptId());
            if (sameScript.isPresent()) {
                return rejected(ResolutionStatus.VERSION_MISMATCH, sameScript.get(),
                        "approved-script-version-mismatch");
            }
            return rejected(ResolutionStatus.UNKNOWN, null, "approved-script-unknown");
        }
        Definition definition = maybe.get();
        if (!definition.enabled()) {
            return rejected(ResolutionStatus.DISABLED, definition, "approved-script-disabled");
        }
        if (definition.revoked()) {
            return rejected(ResolutionStatus.REVOKED, definition, "approved-script-revoked");
        }
        if (!definition.version().equals(invocation.scriptVersion())) {
            return rejected(ResolutionStatus.VERSION_MISMATCH, definition, "approved-script-version-mismatch");
        }
        if (!definition.scriptBodySha256().equals(canonicalHash(invocation.scriptHash()))) {
            return rejected(ResolutionStatus.HASH_MISMATCH, definition, "approved-script-hash-mismatch");
        }
        if (!tenantAllowed(definition, tenantId)) {
            return rejected(ResolutionStatus.TENANT_DENIED, definition, "approved-script-tenant-denied");
        }
        if (nowEpochMillis >= definition.approvalExpiresAtEpochMillis()) {
            return rejected(ResolutionStatus.APPROVAL_EXPIRED, definition, "approved-script-approval-expired");
        }
        Map<String, String> args = normalizeArgs(definition, invocation.args());
        if (args == null) {
            return rejected(ResolutionStatus.ARG_SCHEMA_INVALID, definition, "approved-script-arg-schema-invalid");
        }
        if (args.values().stream().anyMatch(ApprovedRemoteScriptCatalog::containsSecretMaterial)) {
            return rejected(ResolutionStatus.ARG_SECRET_MATERIAL, definition, "approved-script-arg-secret-material");
        }
        String commandLine = renderCommand(definition, args);
        if (commandLine == null
                || !COMMAND_GUARD.decide(commandLine).allowed()
                || !ARGUMENT_POLICY.decide(commandLine).allowed()) {
            return rejected(ResolutionStatus.COMMAND_POLICY_DENIED, definition, "approved-script-command-policy-denied");
        }
        return new Resolution(ResolutionStatus.ALLOWED, definition,
                new PreparedScript(definition, commandLine, args), "approved-script-allowed");
    }

    public static ApprovedRemoteScriptCatalog standard(long permitTtlMillis) {
        return new ApprovedRemoteScriptCatalog(Set.of(
                enabledHostname(permitTtlMillis),
                disabled("DIAG_IPCONFIG", "Network adapter summary", "ipconfig /all\n",
                        "agent-side-ipconfig-argument-policy-not-implemented", permitTtlMillis),
                revoked("COLLECT_SUPPORT_BUNDLE", "Collect support bundle", "tar diagnostic bundle\n",
                        "support-bundle-data-plane-not-implemented", permitTtlMillis)));
    }

    private static Definition enabledHostname(long permitTtlMillis) {
        String body = "hostname\n";
        return new Definition("DIAG_HOSTNAME", "1", "Diagnostic hostname", body, sha256Hex(body),
                "remote-response-release", "security-approval-board", "RR-22.6.2-DIAG-HOSTNAME",
                1_700_000_000_000L, 4_102_444_800_000L, Set.of("*"),
                RemoteOperationCatalog.RiskLevel.LOW,
                Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP),
                RemoteSessionCapability.CONSTRAINED_PTY, Math.min(permitTtlMillis, 60_000L),
                RemoteOperationCatalog.OutputRetention.WORM_TRANSCRIPT,
                RemoteOperationCatalog.RedactionClass.STANDARD_OUTPUT,
                "No host mutation; no cleanup action required.", "hostname", List.of(), true, false, null);
    }

    private static Definition disabled(String id, String displayName, String body, String reason, long permitTtlMillis) {
        return lifecycleBlocked(id, displayName, body, reason, false, permitTtlMillis);
    }

    private static Definition revoked(String id, String displayName, String body, String reason, long permitTtlMillis) {
        return lifecycleBlocked(id, displayName, body, reason, true, permitTtlMillis);
    }

    private static Definition lifecycleBlocked(String id, String displayName, String body, String reason,
                                               boolean revoked, long permitTtlMillis) {
        return new Definition(id, "1", displayName, body, sha256Hex(body),
                "remote-response-release", "security-approval-board", "RR-22.6.2-" + id,
                1_700_000_000_000L, 4_102_444_800_000L, Set.of("*"),
                RemoteOperationCatalog.RiskLevel.MEDIUM,
                Set.of(RemoteOperationCatalog.ApprovalRequirement.WEBAUTHN_STEP_UP,
                        RemoteOperationCatalog.ApprovalRequirement.DUAL_CONTROL),
                RemoteSessionCapability.CONSTRAINED_PTY, Math.min(permitTtlMillis, 60_000L),
                RemoteOperationCatalog.OutputRetention.WORM_TRANSCRIPT,
                RemoteOperationCatalog.RedactionClass.SECRET_SCRUBBED,
                "Blocked entry; no endpoint change is expected.", "hostname", List.of(), revoked, revoked, reason);
    }

    private static Resolution rejected(ResolutionStatus status, Definition definition, String reason) {
        return new Resolution(status, definition, null, reason);
    }

    private static String key(Definition definition) {
        return key(definition.scriptId(), definition.version());
    }

    private Optional<Definition> findAnyVersion(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) {
            return Optional.empty();
        }
        String canonical = scriptId.strip().toUpperCase(Locale.ROOT);
        return definitions.values().stream()
                .filter(definition -> definition.scriptId().equals(canonical))
                .findFirst();
    }

    private static String key(String scriptId, String version) {
        return scriptId + ":" + version;
    }

    private static boolean hasRawScriptText(Invocation invocation) {
        return hasText(invocation.rawScriptText())
                || hasText(invocation.scriptText())
                || hasText(invocation.scriptBody())
                || hasText(invocation.commandLine());
    }

    private static boolean hasText(String raw) {
        return raw != null && !raw.isBlank();
    }

    private static Map<String, String> normalizeArgs(Definition definition, Map<String, String> supplied) {
        Map<String, String> safe = new LinkedHashMap<>();
        Map<String, String> remaining = supplied == null ? new LinkedHashMap<>() : new LinkedHashMap<>(supplied);
        for (ArgumentSpec spec : definition.argsSchema()) {
            String raw = remaining.remove(spec.name());
            if ((raw == null || raw.isBlank()) && spec.defaultValue() != null) {
                raw = spec.defaultValue();
            }
            if (raw == null || raw.isBlank()) {
                if (spec.required()) {
                    return null;
                }
                continue;
            }
            String value = raw.strip();
            if (!spec.accepts(value)) {
                return null;
            }
            safe.put(spec.name(), value);
        }
        if (!remaining.isEmpty()) {
            return null;
        }
        return Map.copyOf(safe);
    }

    private static String renderCommand(Definition definition, Map<String, String> args) {
        String command = definition.commandTemplate();
        for (Map.Entry<String, String> arg : args.entrySet()) {
            command = command.replace("{" + arg.getKey() + "}", arg.getValue());
        }
        return command.contains("{") || command.contains("}") ? null : command.strip();
    }

    private static boolean tenantAllowed(Definition definition, String tenantId) {
        if (definition.tenantIds().contains("*")) {
            return true;
        }
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        return definition.tenantIds().contains(tenantId.strip().toLowerCase(Locale.ROOT));
    }

    private static Set<String> canonicalTenantIds(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("tenant scope is required");
        }
        Set<String> scoped = raw.stream()
                .map(value -> value == null ? "" : value.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (scoped.contains("*") && scoped.size() > 1) {
            throw new IllegalArgumentException("wildcard tenant scope must stand alone");
        }
        for (String tenant : scoped) {
            if (!TENANT.matcher(tenant).matches()) {
                throw new IllegalArgumentException("tenant scope must be wildcard or canonical UUID");
            }
        }
        return scoped;
    }

    private static String canonicalHash(String hash) {
        if (hash == null) {
            return "";
        }
        String canonical = hash.strip().toLowerCase(Locale.ROOT);
        return HASH.matcher(canonical).matches() ? canonical : "";
    }

    private static boolean containsSecretMaterial(String value) {
        return SECRET_SCANNER.redactText(value).total() > 0;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
