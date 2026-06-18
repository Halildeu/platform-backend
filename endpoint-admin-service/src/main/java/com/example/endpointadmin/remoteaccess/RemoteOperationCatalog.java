package com.example.endpointadmin.remoteaccess;

import java.util.Comparator;
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
 * Faz 22.6.1 — the server-owned Remote Response operation catalog. Operators choose an id from this catalog;
 * they do not paste shell text. Unknown ids, disabled ids, and caller-supplied command overrides fail closed
 * before the broker can mint a permit.
 */
public final class RemoteOperationCatalog {

    private static final Pattern ID = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public enum ApprovalRequirement { SESSION_CONSENT, WEBAUTHN_STEP_UP, DUAL_CONTROL }

    public enum OutputRetention { WORM_TRANSCRIPT, SUMMARY_ONLY }

    public enum RedactionClass { STANDARD_OUTPUT, SECRET_SCRUBBED }

    public record Entry(String id,
                        String displayName,
                        RemoteOperation operation,
                        String commandLine,
                        RemoteSessionCapability requiredCapability,
                        RiskLevel riskLevel,
                        ApprovalRequirement approvalRequirement,
                        boolean consentRequired,
                        long permitTtlMillis,
                        OutputRetention outputRetention,
                        RedactionClass redactionClass,
                        boolean enabled,
                        String disabledReason) {
        public Entry {
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("catalog id must be an uppercase operation id");
            }
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("displayName is required");
            }
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(requiredCapability, "requiredCapability");
            Objects.requireNonNull(riskLevel, "riskLevel");
            Objects.requireNonNull(approvalRequirement, "approvalRequirement");
            Objects.requireNonNull(outputRetention, "outputRetention");
            Objects.requireNonNull(redactionClass, "redactionClass");
            if (permitTtlMillis <= 0) {
                throw new IllegalArgumentException("permitTtlMillis must be positive");
            }
            if (enabled && operation == RemoteOperation.PTY_COMMAND) {
                if (commandLine == null || commandLine.isBlank()) {
                    throw new IllegalArgumentException("PTY catalog entries require a server-owned command line");
                }
                if (requiredCapability != RemoteSessionCapability.CONSTRAINED_PTY) {
                    throw new IllegalArgumentException("PTY catalog entries require CONSTRAINED_PTY");
                }
            } else if (commandLine != null && !commandLine.isBlank()) {
                throw new IllegalArgumentException("non-PTY catalog entries must not carry a command line");
            }
            if (!enabled && (disabledReason == null || disabledReason.isBlank())) {
                throw new IllegalArgumentException("disabled entries require a reason");
            }
            commandLine = commandLine == null || commandLine.isBlank() ? null : commandLine.strip();
            disabledReason = disabledReason == null || disabledReason.isBlank()
                    ? null : disabledReason.strip().toLowerCase(Locale.ROOT);
        }
    }

    public enum ResolutionStatus { ALLOWED, UNKNOWN, DISABLED, OVERRIDE_ATTEMPT, OPERATION_MISMATCH }

    public record Resolution(ResolutionStatus status, Entry entry, String reason) {
        public boolean allowed() {
            return status == ResolutionStatus.ALLOWED;
        }
    }

    private final Map<String, Entry> entries;

    public RemoteOperationCatalog(Set<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("at least one catalog entry is required");
        }
        this.entries = entries.stream().collect(Collectors.toUnmodifiableMap(Entry::id, Function.identity()));
    }

    public List<Entry> entries() {
        return entries.values().stream()
                .sorted(Comparator.comparing(Entry::id))
                .toList();
    }

    public Optional<Entry> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(id.strip().toUpperCase(Locale.ROOT)));
    }

    public Resolution resolve(String catalogOperationId, RemoteOperation callerOperation, String callerCommandLine) {
        Optional<Entry> maybe = find(catalogOperationId);
        if (maybe.isEmpty()) {
            return new Resolution(ResolutionStatus.UNKNOWN, null, "catalog-operation-unknown");
        }
        Entry entry = maybe.get();
        if (!entry.enabled()) {
            return new Resolution(ResolutionStatus.DISABLED, entry, "catalog-operation-disabled");
        }
        if (callerOperation != null && callerOperation != entry.operation()) {
            return new Resolution(ResolutionStatus.OPERATION_MISMATCH, entry, "catalog-operation-mismatch");
        }
        if (callerCommandLine != null && !callerCommandLine.isBlank()) {
            return new Resolution(ResolutionStatus.OVERRIDE_ATTEMPT, entry, "catalog-command-override");
        }
        return new Resolution(ResolutionStatus.ALLOWED, entry, "catalog-operation-allowed");
    }

    public static RemoteOperationCatalog standard(long permitTtlMillis) {
        return new RemoteOperationCatalog(Set.of(
                enabled("GET_HOSTNAME", "Get hostname", "hostname", RiskLevel.LOW,
                        ApprovalRequirement.WEBAUTHN_STEP_UP, permitTtlMillis),
                enabled("GET_NETWORK_SUMMARY", "Get network summary", "netstat -a -n", RiskLevel.LOW,
                        ApprovalRequirement.WEBAUTHN_STEP_UP, permitTtlMillis),
                disabled("GET_AGENT_STATUS", "Get agent status", RemoteOperation.PTY_COMMAND,
                        RemoteSessionCapability.CONSTRAINED_PTY, "agent-status-wire-operation-not-implemented",
                        permitTtlMillis),
                disabled("GET_AGENT_VERSION", "Get agent version", RemoteOperation.PTY_COMMAND,
                        RemoteSessionCapability.CONSTRAINED_PTY, "agent-version-wire-operation-not-implemented",
                        permitTtlMillis),
                disabled("GET_SERVICE_STATUS", "Get service status", RemoteOperation.PTY_COMMAND,
                        RemoteSessionCapability.CONSTRAINED_PTY, "service-status-argument-policy-not-implemented",
                        permitTtlMillis),
                disabled("COLLECT_AGENT_LOGS", "Collect agent logs", RemoteOperation.PTY_COMMAND,
                        RemoteSessionCapability.CONSTRAINED_PTY, "log-collection-data-plane-not-implemented",
                        permitTtlMillis),
                disabled("RUN_CERT_AUTOENROLL_PULSE", "Run certificate auto-enroll pulse",
                        RemoteOperation.PTY_COMMAND, RemoteSessionCapability.CONSTRAINED_PTY,
                        "domain-ops-dual-control-connector-required", permitTtlMillis),
                disabled("REFRESH_SOFTWARE_INVENTORY", "Refresh software inventory", RemoteOperation.PTY_COMMAND,
                        RemoteSessionCapability.CONSTRAINED_PTY, "agent-command-bridge-contract-not-implemented",
                        permitTtlMillis)));
    }

    private static Entry enabled(String id, String displayName, String commandLine, RiskLevel riskLevel,
                                 ApprovalRequirement approvalRequirement, long permitTtlMillis) {
        return new Entry(id, displayName, RemoteOperation.PTY_COMMAND, commandLine,
                RemoteSessionCapability.CONSTRAINED_PTY, riskLevel, approvalRequirement, true, permitTtlMillis,
                OutputRetention.WORM_TRANSCRIPT, RedactionClass.STANDARD_OUTPUT, true, null);
    }

    private static Entry disabled(String id, String displayName, RemoteOperation operation,
                                  RemoteSessionCapability capability, String reason, long permitTtlMillis) {
        return new Entry(id, displayName, operation, null, capability, RiskLevel.MEDIUM,
                ApprovalRequirement.DUAL_CONTROL, true, permitTtlMillis, OutputRetention.WORM_TRANSCRIPT,
                RedactionClass.SECRET_SCRUBBED, false, reason);
    }
}
