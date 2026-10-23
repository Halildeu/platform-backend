package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reloading, public-only trust boundary for Cross-AI and runtime DSSE.
 *
 * <p>Trust roots and revocations are public evidence, never credentials. Every
 * verification reloads a confined immutable projected-volume target so an
 * atomic ConfigMap rotation is observed without accepting a symlink escape or
 * stale process-start state. There is
 * no local key, unsigned or provider fallback.</p>
 */
public final class ViewOnlyPublicTrustStore {
    private static final int MAX_PUBLIC_FILE_BYTES = 1_048_576;
    private static final int MAX_PAYLOAD_BYTES = 524_288;
    private static final String DSSE_ENVELOPE_DOMAIN = "faz22.6/view-only/dsse-envelope/v1";
    private static final String RUNTIME_ROOT_DOMAIN = "faz22.6/view-only/runtime-trust-root/v1";
    private static final String REVOCATIONS_PAYLOAD_TYPE =
            "application/vnd.acik.cross-ai-deployment-revocations.v1+json";
    private static final byte[] ED25519_SPKI_PREFIX = java.util.HexFormat.of().parseHex(
            "302a300506032b6570032100");

    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final ViewOnlyDigest digest;
    private final Clock clock;
    private final Path crossAiTrustRootFile;
    private final String crossAiTrustRootSha256;
    private final Path crossAiRevocationsFile;
    private final Path runtimeTrustRootFile;
    private final String runtimeTrustRootSha256;

    public ViewOnlyPublicTrustStore(RemoteViewJsonCanonicalizer canonicalizer,
                                    Clock clock,
                                    ViewOnlyAuthorityProperties properties) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.digest = new ViewOnlyDigest(canonicalizer);
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(properties, "properties").validateActivation();
        this.crossAiTrustRootFile = Path.of(properties.getCrossAiTrustRootFile()).toAbsolutePath().normalize();
        this.crossAiTrustRootSha256 = properties.getCrossAiTrustRootSha256();
        this.crossAiRevocationsFile = Path.of(properties.getCrossAiRevocationsFile()).toAbsolutePath().normalize();
        this.runtimeTrustRootFile = Path.of(properties.getRuntimeTrustRootFile()).toAbsolutePath().normalize();
        this.runtimeTrustRootSha256 = properties.getRuntimeTrustRootSha256();
    }

    /** Fails startup before any route is exposed when public authority is not complete. */
    public void probeReady() {
        CrossAiMaterial crossAi = loadCrossAi();
        if (!crossAi.keysByRole().keySet().containsAll(
                Set.of("provider-review", "coordinator", "revocation", "runner-management"))) {
            throw invalid("Cross-AI trust root is missing a mandatory role");
        }
        requireCurrentlyActiveRoles(
                crossAi.keysByRole(), crossAi.revocations(),
                Set.of("provider-review", "coordinator", "revocation", "runner-management"),
                "Cross-AI");
        RuntimeMaterial runtime = loadRuntime();
        if (!runtime.keysByRole().keySet().containsAll(Set.of("runtime-attestor", "checkpoint-signer"))) {
            throw invalid("runtime trust root is missing an attestor or checkpoint signer");
        }
        requireCurrentlyActiveRoles(
                runtime.keysByRole(), runtime.revocations(),
                Set.of("runtime-attestor", "checkpoint-signer"), "runtime");
    }

    /** Returns the currently usable public checkpoint signer pinned by exact key ID. */
    public RuntimeSignerAuthority checkpointSignerAuthority(String expectedKeyId) {
        RuntimeMaterial runtime = loadRuntime();
        Instant now = clock.instant();
        List<TrustKey> candidates = runtime.keysByRole().getOrDefault("checkpoint-signer", List.of());
        TrustKey selected = candidates.stream()
                .filter(key -> key.keyId().equals(expectedKeyId))
                .filter(key -> currentlyActive(key, runtime.revocations(), now))
                .findFirst()
                .orElseThrow(() -> invalid(
                        "configured Transit signer is not the active runtime checkpoint authority"));
        return new RuntimeSignerAuthority(
                selected.keyId(), selected.publicKey(), sha256(selected.publicKey()));
    }

    public VerifiedDsse verifyCrossAi(JsonNode envelope,
                                      String expectedPayloadType,
                                      String expectedRole,
                                      Instant issuedAt) {
        CrossAiMaterial material = loadCrossAi();
        VerifiedDsse verified = verify(
                envelope, expectedPayloadType, expectedRole, issuedAt,
                material.keys(), material.revocations());
        TrustKey key = material.keys().get(verified.keyId());
        if (key == null || !currentlyActive(key, material.revocations(), clock.instant())) {
            throw invalid("Cross-AI DSSE signing key is not active at verification time");
        }
        return verified;
    }

    public VerifiedDsse verifyRuntime(JsonNode envelope,
                                      String expectedPayloadType,
                                      String expectedRole,
                                      Instant issuedAt) {
        RuntimeMaterial material = loadRuntime();
        VerifiedDsse verified = verify(envelope, expectedPayloadType, expectedRole, issuedAt,
                material.keys(), material.revocations());
        TrustKey key = material.keys().get(verified.keyId());
        if (key == null || !currentlyActive(key, material.revocations(), clock.instant())) {
            throw invalid("runtime DSSE signing key is not active at verification time");
        }
        return verified;
    }

    public boolean isCrossAiRevoked(String type, String id, Instant at) {
        return isRevoked(loadCrossAi().revocations(), type, id, at);
    }

    private CrossAiMaterial loadCrossAi() {
        JsonNode root = readObject(crossAiTrustRootFile, "Cross-AI trust root");
        exactFields(root, Set.of(
                "schemaVersion", "trustRootId", "sourcePublicKeysetSha256", "issuedAt", "expiresAt",
                "maxClockSkewSeconds", "requiredProviderFamilies", "minimumProviderFamilies",
                "minimumDirectProviderRoutes", "keys"), "Cross-AI trust root");
        requireText(root, "schemaVersion", "acik.cross-ai-deployment-trust-root.v2");
        requireUuid(root, "trustRootId");
        ViewOnlyDigest.requireSha256(text(root, "sourcePublicKeysetSha256"), "sourcePublicKeysetSha256");
        if (!crossAiTrustRootSha256.equals(canonicalizer.digest(root))) {
            throw invalid("Cross-AI trust root differs from its configured JCS digest");
        }
        Instant now = clock.instant();
        Instant rootStart = instant(root, "issuedAt");
        Instant rootEnd = instant(root, "expiresAt");
        int skewSeconds = integer(root, "maxClockSkewSeconds", 0, 300);
        Duration skew = Duration.ofSeconds(skewSeconds);
        if (rootStart.isAfter(now.plus(skew)) || rootEnd.isBefore(now.minus(skew))
                || !rootStart.isBefore(rootEnd)
                || Duration.between(rootStart, rootEnd).compareTo(Duration.ofHours(720)) > 0) {
            throw invalid("Cross-AI trust root is outside its active lifetime");
        }
        JsonNode families = root.get("requiredProviderFamilies");
        if (families == null || !families.isArray() || families.size() != 1
                || !"openai".equals(families.get(0).asText())
                || integer(root, "minimumProviderFamilies", 1, 1) != 1
                || integer(root, "minimumDirectProviderRoutes", 1, 1) != 1) {
            throw invalid("Cross-AI trust root is not the canonical Codex-only authority");
        }
        Map<String, TrustKey> keys = parseCrossAiKeys(root.get("keys"));
        Map<String, List<TrustKey>> byRole = byRole(keys);
        validateCrossAiRoleTopology(byRole);
        JsonNode revocationsEnvelope = readObject(crossAiRevocationsFile, "Cross-AI signed revocations");
        JsonNode untrustedPayload = decodePayload(revocationsEnvelope, REVOCATIONS_PAYLOAD_TYPE);
        Instant revocationsIssuedAt = instant(untrustedPayload, "issuedAt");
        VerifiedDsse verifiedRevocations = verify(
                revocationsEnvelope, REVOCATIONS_PAYLOAD_TYPE, "revocation", revocationsIssuedAt,
                keys, List.of());
        JsonNode revocations = verifiedRevocations.payload();
        exactFields(revocations, Set.of("schemaVersion", "revocationSetId", "issuedAt", "nextUpdate", "entries"),
                "Cross-AI revocations payload");
        requireText(revocations, "schemaVersion", "acik.cross-ai-deployment-revocations.v1");
        requireUuid(revocations, "revocationSetId");
        Instant nextUpdate = instant(revocations, "nextUpdate");
        if (revocationsIssuedAt.isAfter(now.plus(skew)) || nextUpdate.isBefore(now.minus(skew))
                || !revocationsIssuedAt.isBefore(nextUpdate)
                || Duration.between(revocationsIssuedAt, nextUpdate).compareTo(Duration.ofMinutes(60)) > 0) {
            throw invalid("Cross-AI signed revocations are stale or outside the 60-minute bound");
        }
        List<Revocation> parsedRevocations = parseCrossAiRevocations(revocations.get("entries"));
        return new CrossAiMaterial(keys, byRole, parsedRevocations);
    }

    private RuntimeMaterial loadRuntime() {
        JsonNode root = readObject(runtimeTrustRootFile, "runtime trust root");
        exactFields(root, Set.of(
                "schemaVersion", "activationState", "trustRootId", "digestDomain", "algorithm",
                "keys", "revocations", "generatedAt"), "runtime trust root");
        requireText(root, "schemaVersion", "faz22.6.viewOnlyRuntimeTrustRoot.v1");
        requireText(root, "activationState", "active");
        requireText(root, "trustRootId", "faz22-view-only-runtime-test-v1");
        requireText(root, "digestDomain", RUNTIME_ROOT_DOMAIN);
        requireText(root, "algorithm", "ed25519");
        String actualDigest = digest.domainDigest(RUNTIME_ROOT_DOMAIN, "trustRoot", root);
        if (!runtimeTrustRootSha256.equals(actualDigest)) {
            throw invalid("runtime trust root differs from its configured domain digest");
        }
        Map<String, TrustKey> keys = parseRuntimeKeys(root.get("keys"));
        List<Revocation> revocations = parseRuntimeRevocations(root.get("revocations"));
        return new RuntimeMaterial(keys, byRole(keys), revocations);
    }

    private VerifiedDsse verify(JsonNode envelope,
                                String expectedPayloadType,
                                String expectedRole,
                                Instant issuedAt,
                                Map<String, TrustKey> keys,
                                List<Revocation> revocations) {
        JsonNode payload = decodePayload(envelope, expectedPayloadType);
        JsonNode signatureEntry = envelope.get("signatures").get(0);
        exactFields(signatureEntry, Set.of("keyid", "sig"), "DSSE signature");
        String keyId = text(signatureEntry, "keyid");
        TrustKey key = keys.get(keyId);
        if (key == null || !expectedRole.equals(key.role())
                || issuedAt.isBefore(key.notBefore()) || !issuedAt.isBefore(key.notAfter())
                || isRevoked(revocations, "key", keyId, issuedAt)) {
            throw invalid("DSSE key is unknown, role-mismatched, expired or revoked");
        }
        byte[] payloadBytes = canonicalizer.canonicalBytes(payload);
        String encodedPayload = text(envelope, "payload");
        if (!Base64.getEncoder().encodeToString(payloadBytes).equals(encodedPayload)) {
            throw invalid("DSSE payload is not canonical JCS encoded as canonical base64");
        }
        byte[] signatureBytes;
        String encodedSignature = text(signatureEntry, "sig");
        try {
            signatureBytes = Base64.getDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException invalidBase64) {
            throw invalid("DSSE signature is not strict base64", invalidBase64);
        }
        try {
            if (signatureBytes.length != 64
                    || !Base64.getEncoder().encodeToString(signatureBytes).equals(encodedSignature)
                    || !verifyEd25519(
                    key.publicKey(), pae(expectedPayloadType, payloadBytes), signatureBytes)) {
                throw invalid("DSSE Ed25519 signature verification failed");
            }
        } finally {
            Arrays.fill(signatureBytes, (byte) 0);
        }
        return new VerifiedDsse(
                payload.deepCopy(), digest.domainDigest(DSSE_ENVELOPE_DOMAIN, "envelope", envelope), keyId);
    }

    private JsonNode decodePayload(JsonNode envelope, String expectedPayloadType) {
        exactFields(envelope, Set.of("payloadType", "payload", "signatures"), "DSSE envelope");
        requireText(envelope, "payloadType", expectedPayloadType);
        JsonNode signatures = envelope.get("signatures");
        if (signatures == null || !signatures.isArray() || signatures.size() != 1) {
            throw invalid("DSSE envelope must contain exactly one signature");
        }
        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getDecoder().decode(text(envelope, "payload"));
        } catch (IllegalArgumentException invalidBase64) {
            throw invalid("DSSE payload is not strict base64", invalidBase64);
        }
        if (payloadBytes.length == 0 || payloadBytes.length > MAX_PAYLOAD_BYTES) {
            throw invalid("DSSE payload is empty or outside its hard bound");
        }
        String raw;
        try {
            raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payloadBytes)).toString();
        } catch (Exception invalidUtf8) {
            throw invalid("DSSE payload is not strict UTF-8", invalidUtf8);
        } finally {
            Arrays.fill(payloadBytes, (byte) 0);
        }
        try {
            return canonicalizer.strictParse(raw);
        } catch (RuntimeException invalidJson) {
            throw invalid("DSSE payload is not strict JSON", invalidJson);
        }
    }

    private Map<String, TrustKey> parseCrossAiKeys(JsonNode entries) {
        if (entries == null || !entries.isArray() || entries.size() < 4 || entries.size() > 5) {
            throw invalid("Cross-AI trust key set is outside its exact bound");
        }
        Map<String, TrustKey> parsed = new HashMap<>();
        Set<String> publicKeys = new HashSet<>();
        for (JsonNode entry : entries) {
            exactFields(entry, Set.of(
                    "keyId", "role", "publicKeyBase64", "notBefore", "notAfter", "providerFamily",
                    "allowedChannels", "allowedModelIds", "allowedModelIdentityClasses", "directProviderCli"),
                    "Cross-AI trust key");
            String keyId = text(entry, "keyId");
            String role = text(entry, "role");
            if (!keyId.matches("^vault-transit://[a-z0-9/_-]+#v[1-9][0-9]*$")) {
                throw invalid("Cross-AI key ID is outside the exact public-root contract");
            }
            byte[] publicKey = rawEd25519(entry, "publicKeyBase64");
            String fingerprint = Base64.getEncoder().encodeToString(publicKey);
            if (!publicKeys.add(fingerprint)) {
                throw invalid("one Ed25519 public key cannot serve two Cross-AI roles");
            }
            Instant notBefore = instant(entry, "notBefore");
            Instant notAfter = instant(entry, "notAfter");
            if (!notBefore.isBefore(notAfter)
                    || ("provider-review".equals(role)
                    && Duration.between(notBefore, notAfter).compareTo(Duration.ofHours(168)) > 0)) {
                throw invalid("Cross-AI key lifetime is invalid");
            }
            String family = entry.path("providerFamily").isNull() ? null : text(entry, "providerFamily");
            String channel = singleOrNull(entry.get("allowedChannels"));
            Set<String> models = textSet(entry.get("allowedModelIds"), 2, "allowedModelIds");
            String identityClass = singleOrNull(entry.get("allowedModelIdentityClasses"));
            Boolean direct = entry.path("directProviderCli").isNull()
                    ? null : entry.path("directProviderCli").booleanValue();
            if ("provider-review".equals(role)) {
                if (!"openai".equals(family) || !"openai-codex".equals(channel)
                        || !models.equals(Set.of("gpt-5.3-codex-spark", "gpt-5.6-sol"))
                        || !"gpt-5.3-codex-spark".equals(entry.get("allowedModelIds").get(0).asText())
                        || !"gpt-5.6-sol".equals(entry.get("allowedModelIds").get(1).asText())
                        || !"trusted-launch-attested".equals(identityClass)
                        || !Boolean.TRUE.equals(direct)) {
                    throw invalid("provider-review key is not the exact direct Codex SOL route");
                }
            } else if (!Set.of("coordinator", "revocation", "runner-management").contains(role)
                    || family != null || channel != null || !models.isEmpty()
                    || identityClass != null || direct != null) {
                throw invalid("Cross-AI non-provider key attribution is invalid");
            }
            if (parsed.put(keyId, new TrustKey(
                    keyId, role, publicKey, notBefore, notAfter, family, channel,
                    models, identityClass, direct)) != null) {
                throw invalid("Cross-AI key ID is duplicated");
            }
        }
        return parsed;
    }

    private static void validateCrossAiRoleTopology(Map<String, List<TrustKey>> byRole) {
        if (!byRole.keySet().equals(Set.of(
                "provider-review", "coordinator", "revocation", "runner-management"))) {
            throw invalid("Cross-AI trust root role set is not exact");
        }
        for (String singleton : Set.of("coordinator", "revocation", "runner-management")) {
            List<TrustKey> keys = byRole.get(singleton);
            String expectedPrefix = "vault-transit://cross-ai/" + singleton + "#v";
            if (keys.size() != 1 || !keys.get(0).keyId().startsWith(expectedPrefix)) {
                throw invalid("Cross-AI singleton role route is not exact");
            }
        }
        List<TrustKey> providerKeys = byRole.get("provider-review").stream()
                .sorted(java.util.Comparator.comparingInt(key -> transitVersion(key.keyId())))
                .toList();
        if (providerKeys.isEmpty() || providerKeys.size() > 2
                || providerKeys.stream().anyMatch(key ->
                !key.keyId().startsWith("vault-transit://cross-ai/openai#v"))) {
            throw invalid("Cross-AI provider-review route set is not exact");
        }
        if (providerKeys.size() == 2) {
            TrustKey earlier = providerKeys.get(0);
            TrustKey later = providerKeys.get(1);
            if (transitVersion(later.keyId()) != transitVersion(earlier.keyId()) + 1
                    || Duration.between(later.notBefore(), earlier.notAfter())
                    .compareTo(Duration.ofHours(24)) < 0) {
                throw invalid("Cross-AI provider-review rotation is not consecutive with 24-hour overlap");
            }
        }
    }

    private static int transitVersion(String keyId) {
        return Integer.parseInt(keyId.substring(keyId.lastIndexOf("#v") + 2));
    }

    private Map<String, TrustKey> parseRuntimeKeys(JsonNode entries) {
        if (entries == null || !entries.isArray() || entries.size() != 2) {
            throw invalid("runtime trust root must contain exactly two role-distinct keys");
        }
        Map<String, TrustKey> parsed = new HashMap<>();
        for (JsonNode entry : entries) {
            exactFields(entry, Set.of(
                    "keyId", "role", "version", "publicKeyBase64", "notBefore", "notAfter", "state"),
                    "runtime trust key");
            String keyId = text(entry, "keyId");
            String role = text(entry, "role");
            int version = integer(entry, "version", 1, 1_000_000);
            String expectedKeyId = "runtime-attestor".equals(role)
                    ? "vault-transit://endpoint-admin/view-only-runtime-attestor#v" + version
                    : "vault-transit://endpoint-admin/view-only-checkpoint#v" + version;
            if (!Set.of("runtime-attestor", "checkpoint-signer").contains(role)
                    || !"active".equals(text(entry, "state"))
                    || !keyId.equals(expectedKeyId)) {
                throw invalid("runtime trust key role, state or version is invalid");
            }
            TrustKey key = new TrustKey(
                    keyId, role, rawEd25519(entry, "publicKeyBase64"),
                    instant(entry, "notBefore"), instant(entry, "notAfter"),
                    null, null, Set.of(), null, null);
            if (!key.notBefore().isBefore(key.notAfter()) || parsed.put(keyId, key) != null) {
                throw invalid("runtime trust key lifetime or ID is invalid");
            }
        }
        if (new HashSet<>(parsed.values().stream().map(TrustKey::publicKeyBase64).toList()).size() != 2) {
            throw invalid("runtime roles must use distinct Ed25519 public keys");
        }
        return parsed;
    }

    private List<Revocation> parseCrossAiRevocations(JsonNode entries) {
        if (entries == null || !entries.isArray() || entries.size() > 10_000) {
            throw invalid("Cross-AI revocation entries are invalid");
        }
        List<Revocation> parsed = new ArrayList<>();
        for (JsonNode entry : entries) {
            exactFields(entry, Set.of("type", "id", "effectiveAt", "reasonCode"), "Cross-AI revocation");
            String type = text(entry, "type");
            if (!Set.of("key", "bundle", "review", "subject", "grant", "runner-lease").contains(type)) {
                throw invalid("Cross-AI revocation type is unknown");
            }
            parsed.add(new Revocation(type, text(entry, "id"), instant(entry, "effectiveAt")));
        }
        return List.copyOf(parsed);
    }

    private List<Revocation> parseRuntimeRevocations(JsonNode entries) {
        if (entries == null || !entries.isArray() || entries.size() > 32) {
            throw invalid("runtime revocation entries are invalid");
        }
        List<Revocation> parsed = new ArrayList<>();
        for (JsonNode entry : entries) {
            exactFields(entry, Set.of("keyId", "revokedAt", "reasonCode"), "runtime revocation");
            parsed.add(new Revocation("key", text(entry, "keyId"), instant(entry, "revokedAt")));
        }
        return List.copyOf(parsed);
    }

    private void requireCurrentlyActiveRoles(Map<String, List<TrustKey>> keysByRole,
                                             List<Revocation> revocations,
                                             Set<String> roles,
                                             String label) {
        Instant now = clock.instant();
        for (String role : roles) {
            if (keysByRole.getOrDefault(role, List.of()).stream()
                    .noneMatch(key -> currentlyActive(key, revocations, now))) {
                throw invalid(label + " trust root has no currently active " + role + " key");
            }
        }
    }

    private static boolean currentlyActive(TrustKey key, List<Revocation> revocations, Instant at) {
        return !at.isBefore(key.notBefore()) && at.isBefore(key.notAfter())
                && !isRevoked(revocations, "key", key.keyId(), at);
    }

    private JsonNode readObject(Path path, String label) {
        byte[] bytes = null;
        try {
            bytes = ViewOnlySecureProjectedFileReader.read(path, MAX_PUBLIC_FILE_BYTES);
            if (bytes.length == 0 || bytes.length > MAX_PUBLIC_FILE_BYTES) {
                throw materialUnavailable(label + " is absent or outside its hard size bound", null);
            }
            String raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode value = canonicalizer.strictParse(raw);
            if (!value.isObject()) {
                throw invalid(label + " must be a JSON object");
            }
            return value;
        } catch (ViewOnlyAuthorityException known) {
            throw known;
        } catch (java.io.IOException readFailure) {
            throw materialUnavailable(label + " could not be read from its confined projection", readFailure);
        } catch (Exception failure) {
            throw invalid(label + " could not be read or verified", failure);
        } finally {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private static byte[] pae(String payloadType, byte[] payload) {
        byte[] type = payloadType.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = ("DSSEv1 " + type.length + " ").getBytes(StandardCharsets.UTF_8);
        byte[] middle = (" " + payload.length + " ").getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefix.length + type.length + middle.length + payload.length];
        int offset = 0;
        System.arraycopy(prefix, 0, result, offset, prefix.length);
        offset += prefix.length;
        System.arraycopy(type, 0, result, offset, type.length);
        offset += type.length;
        System.arraycopy(middle, 0, result, offset, middle.length);
        offset += middle.length;
        System.arraycopy(payload, 0, result, offset, payload.length);
        return result;
    }

    private static boolean verifyEd25519(byte[] rawPublicKey, byte[] message, byte[] signature) {
        try {
            byte[] spki = new byte[ED25519_SPKI_PREFIX.length + rawPublicKey.length];
            System.arraycopy(ED25519_SPKI_PREFIX, 0, spki, 0, ED25519_SPKI_PREFIX.length);
            System.arraycopy(rawPublicKey, 0, spki, ED25519_SPKI_PREFIX.length, rawPublicKey.length);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (Exception failure) {
            throw invalid("Ed25519 verification is unavailable", failure);
        }
    }

    private static byte[] rawEd25519(JsonNode object, String field) {
        try {
            byte[] decoded = Base64.getDecoder().decode(text(object, field));
            if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(text(object, field))) {
                Arrays.fill(decoded, (byte) 0);
                throw invalid(field + " is not one canonical 32-byte Ed25519 public key");
            }
            return decoded;
        } catch (IllegalArgumentException invalidBase64) {
            throw invalid(field + " is not strict base64", invalidBase64);
        }
    }

    private static Map<String, List<TrustKey>> byRole(Map<String, TrustKey> keys) {
        Map<String, List<TrustKey>> result = new HashMap<>();
        keys.values().forEach(key -> result.computeIfAbsent(key.role(), ignored -> new ArrayList<>()).add(key));
        return result;
    }

    private static boolean isRevoked(List<Revocation> entries, String type, String id, Instant at) {
        return entries.stream().anyMatch(entry -> entry.type().equals(type)
                && entry.id().equals(id) && !entry.effectiveAt().isAfter(at));
    }

    private static String singleOrNull(JsonNode values) {
        if (values == null || !values.isArray() || values.size() > 1) {
            throw invalid("trust key attribution arrays must contain at most one value");
        }
        return values.isEmpty() ? null : values.get(0).asText();
    }

    private static Set<String> textSet(JsonNode values, int maximum, String label) {
        if (values == null || !values.isArray() || values.size() > maximum) {
            throw invalid(label + " is outside its exact array bound");
        }
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank() || !result.add(value.textValue())) {
                throw invalid(label + " contains a blank, non-text or duplicate value");
            }
        }
        return Set.copyOf(result);
    }

    private static void requireUuid(JsonNode object, String field) {
        String value = text(object, field);
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw invalid(field + " is not a canonical UUID");
            }
        } catch (IllegalArgumentException invalidUuid) {
            throw invalid(field + " is not a canonical UUID", invalidUuid);
        }
    }

    static void exactFields(JsonNode object, Set<String> expected, String label) {
        if (object == null || !object.isObject()) {
            throw invalid(label + " must be an object");
        }
        Set<String> actual = new HashSet<>();
        Iterator<String> names = object.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid(label + " fields do not match the exact contract");
        }
    }

    static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return value.textValue();
    }

    static void requireText(JsonNode object, String field, String expected) {
        if (!expected.equals(text(object, field))) {
            throw invalid(field + " does not match its pinned value");
        }
    }

    static Instant instant(JsonNode object, String field) {
        try {
            return Instant.parse(text(object, field));
        } catch (Exception invalidTime) {
            throw invalid(field + " must be a canonical UTC instant", invalidTime);
        }
    }

    static int integer(JsonNode object, String field, int minimum, int maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw invalid(field + " is outside its exact integer bound");
        }
        return value.intValue();
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return invalid(message, null);
    }

    private static ViewOnlyAuthorityException invalid(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message, cause);
    }

    private static ViewOnlyAuthorityException materialUnavailable(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(
                ViewOnlyAuthorityError.AUTHORITY_MATERIAL_UNAVAILABLE, message, cause);
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record VerifiedDsse(JsonNode payload, String envelopeSha256, String keyId) {
        public VerifiedDsse {
            payload = payload.deepCopy();
            ViewOnlyDigest.requireSha256(envelopeSha256, "DSSE envelopeSha256");
        }

        @Override
        public JsonNode payload() {
            return payload.deepCopy();
        }
    }

    public record RuntimeSignerAuthority(String keyId, byte[] publicKey, String publicKeySha256) {
        public RuntimeSignerAuthority {
            publicKey = publicKey.clone();
            ViewOnlyDigest.requireSha256(publicKeySha256, "runtime signer publicKeySha256");
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }
    }

    private record TrustKey(
            String keyId,
            String role,
            byte[] publicKey,
            Instant notBefore,
            Instant notAfter,
            String providerFamily,
            String channel,
            Set<String> modelIds,
            String modelIdentityClass,
            Boolean directProviderCli) {
        TrustKey {
            publicKey = publicKey.clone();
            modelIds = Set.copyOf(modelIds);
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }

        String publicKeyBase64() {
            return Base64.getEncoder().encodeToString(publicKey);
        }
    }

    private record Revocation(String type, String id, Instant effectiveAt) {
    }

    private record CrossAiMaterial(
            Map<String, TrustKey> keys,
            Map<String, List<TrustKey>> keysByRole,
            List<Revocation> revocations) {
    }

    private record RuntimeMaterial(
            Map<String, TrustKey> keys,
            Map<String, List<TrustKey>> keysByRole,
            List<Revocation> revocations) {
    }
}
