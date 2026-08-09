package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewOnlyPublicTrustStoreTest {
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final String CROSS_PAYLOAD_TYPE = "application/vnd.acik.test.cross-ai.v1+json";
    private static final String RUNTIME_PAYLOAD_TYPE = "application/vnd.acik.test.runtime.v1+json";

    @TempDir
    Path directory;

    private RemoteViewJsonCanonicalizer canonicalizer;
    private Map<String, KeyPair> crossKeys;
    private Map<String, KeyPair> runtimeKeys;
    private ViewOnlyAuthorityProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        canonicalizer = new RemoteViewJsonCanonicalizer();
        crossKeys = keys("provider-review", "coordinator", "revocation", "runner-management");
        runtimeKeys = keys("runtime-attestor", "checkpoint-signer");

        ObjectNode crossRoot = crossRoot();
        ObjectNode revocationsPayload = canonicalizer.mapper().createObjectNode();
        revocationsPayload.put("schemaVersion", "acik.cross-ai-deployment-revocations.v1");
        revocationsPayload.put("revocationSetId", "123e4567-e89b-42d3-a456-426614174010");
        revocationsPayload.put("issuedAt", NOW.minusSeconds(60).toString());
        revocationsPayload.put("nextUpdate", NOW.plusSeconds(3500).toString());
        revocationsPayload.putArray("entries");
        ObjectNode revocationsEnvelope = envelope(
                "application/vnd.acik.cross-ai-deployment-revocations.v1+json",
                revocationsPayload, "revocation", crossKeys.get("revocation"));
        ObjectNode runtimeRoot = runtimeRoot();

        Path crossFile = directory.resolve("cross-root.json");
        Path revocationsFile = directory.resolve("revocations.json");
        Path runtimeFile = directory.resolve("runtime-root.json");
        Files.write(crossFile, canonicalizer.canonicalBytes(crossRoot));
        Files.write(revocationsFile, canonicalizer.canonicalBytes(revocationsEnvelope));
        Files.write(runtimeFile, canonicalizer.canonicalBytes(runtimeRoot));

        properties = ViewOnlyAuthorityPropertiesTest.enabledProperties();
        properties.setCrossAiTrustRootFile(crossFile.toString());
        properties.setCrossAiTrustRootSha256(canonicalizer.digest(crossRoot));
        properties.setCrossAiRevocationsFile(revocationsFile.toString());
        properties.setRuntimeTrustRootFile(runtimeFile.toString());
        properties.setRuntimeTrustRootSha256(new ViewOnlyDigest(canonicalizer).domainDigest(
                "faz22.6/view-only/runtime-trust-root/v1", "trustRoot", runtimeRoot));
    }

    @Test
    void verifiesRolePinnedCrossAiAndRuntimeDsse() {
        ViewOnlyPublicTrustStore store = store();
        store.probeReady();
        ObjectNode crossPayload = canonicalizer.mapper().createObjectNode()
                .put("issuedAt", NOW.minusSeconds(5).toString()).put("kind", "coordinator");
        ObjectNode runtimePayload = canonicalizer.mapper().createObjectNode()
                .put("issuedAt", NOW.minusSeconds(5).toString()).put("kind", "attestor");

        ViewOnlyPublicTrustStore.VerifiedDsse cross = store.verifyCrossAi(
                envelope(CROSS_PAYLOAD_TYPE, crossPayload, "coordinator", crossKeys.get("coordinator")),
                CROSS_PAYLOAD_TYPE, "coordinator", NOW.minusSeconds(5));
        ViewOnlyPublicTrustStore.VerifiedDsse runtime = store.verifyRuntime(
                envelope(RUNTIME_PAYLOAD_TYPE, runtimePayload, "runtime-attestor",
                        runtimeKeys.get("runtime-attestor")),
                RUNTIME_PAYLOAD_TYPE, "runtime-attestor", NOW.minusSeconds(5));

        assertThat(cross.envelopeSha256()).matches("sha256:[0-9a-f]{64}");
        assertThat(runtime.envelopeSha256()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void rejectsWrongRoleAndRuntimeKeySubstitutionUnderSameKeyId() throws Exception {
        ViewOnlyPublicTrustStore store = store();
        ObjectNode payload = canonicalizer.mapper().createObjectNode()
                .put("issuedAt", NOW.minusSeconds(5).toString());
        assertThatThrownBy(() -> store.verifyCrossAi(
                envelope(CROSS_PAYLOAD_TYPE, payload, "coordinator", crossKeys.get("coordinator")),
                CROSS_PAYLOAD_TYPE, "provider-review", NOW.minusSeconds(5)))
                .isInstanceOf(ViewOnlyAuthorityException.class);

        ObjectNode substituted = runtimeRoot();
        byte[] foreign = rawPublicKey(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        ((ObjectNode) substituted.withArray("keys").get(0)).put(
                "publicKeyBase64", Base64.getEncoder().encodeToString(foreign));
        Files.write(Path.of(properties.getRuntimeTrustRootFile()), canonicalizer.canonicalBytes(substituted));

        assertThatThrownBy(store::probeReady)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void rejectsNonCanonicalSignatureBase64() {
        ViewOnlyPublicTrustStore store = store();
        ObjectNode payload = canonicalizer.mapper().createObjectNode()
                .put("issuedAt", NOW.minusSeconds(5).toString());
        ObjectNode signed = envelope(
                CROSS_PAYLOAD_TYPE, payload, "coordinator", crossKeys.get("coordinator"));
        ObjectNode signature = (ObjectNode) signed.withArray("signatures").get(0);
        signature.put("sig", signature.get("sig").textValue().replace("=", ""));

        assertThatThrownBy(() -> store.verifyCrossAi(
                signed, CROSS_PAYLOAD_TYPE, "coordinator", NOW.minusSeconds(5)))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void rejectsBackdatedDsseAfterItsKeyIsRevokedAtVerificationTime() throws Exception {
        ObjectNode revocationsPayload = canonicalizer.mapper().createObjectNode();
        revocationsPayload.put("schemaVersion", "acik.cross-ai-deployment-revocations.v1");
        revocationsPayload.put("revocationSetId", "123e4567-e89b-42d3-a456-426614174012");
        revocationsPayload.put("issuedAt", NOW.minusSeconds(30).toString());
        revocationsPayload.put("nextUpdate", NOW.plusSeconds(3500).toString());
        revocationsPayload.putArray("entries").addObject()
                .put("type", "key")
                .put("id", keyId("coordinator"))
                .put("effectiveAt", NOW.minusSeconds(10).toString())
                .put("reasonCode", "compromised");
        Files.write(Path.of(properties.getCrossAiRevocationsFile()), canonicalizer.canonicalBytes(envelope(
                "application/vnd.acik.cross-ai-deployment-revocations.v1+json",
                revocationsPayload, "revocation", crossKeys.get("revocation"))));
        ObjectNode crossPayload = canonicalizer.mapper().createObjectNode()
                .put("issuedAt", NOW.minusSeconds(20).toString());

        assertThatThrownBy(() -> store().verifyCrossAi(
                envelope(CROSS_PAYLOAD_TYPE, crossPayload, "coordinator", crossKeys.get("coordinator")),
                CROSS_PAYLOAD_TYPE, "coordinator", NOW.minusSeconds(20)))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("verification time");

        ObjectNode runtimeRoot = runtimeRoot();
        runtimeRoot.withArray("revocations").addObject()
                .put("keyId", keyId("runtime-attestor"))
                .put("revokedAt", NOW.minusSeconds(10).toString())
                .put("reasonCode", "compromised");
        Files.write(Path.of(properties.getRuntimeTrustRootFile()), canonicalizer.canonicalBytes(runtimeRoot));
        properties.setRuntimeTrustRootSha256(new ViewOnlyDigest(canonicalizer).domainDigest(
                "faz22.6/view-only/runtime-trust-root/v1", "trustRoot", runtimeRoot));
        ObjectNode runtimePayload = canonicalizer.mapper().createObjectNode()
                .put("issuedAt", NOW.minusSeconds(20).toString());

        assertThatThrownBy(() -> store().verifyRuntime(
                envelope(RUNTIME_PAYLOAD_TYPE, runtimePayload, "runtime-attestor",
                        runtimeKeys.get("runtime-attestor")),
                RUNTIME_PAYLOAD_TYPE, "runtime-attestor", NOW.minusSeconds(20)))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("verification time");
    }

    @Test
    void rejectsSemanticallyBroadenedProviderRootEvenWithMatchingFilePin() throws Exception {
        ObjectNode broadened = crossRoot();
        ObjectNode provider = (ObjectNode) java.util.stream.StreamSupport.stream(
                        broadened.withArray("keys").spliterator(), false)
                .filter(key -> "provider-review".equals(key.get("role").textValue()))
                .findFirst().orElseThrow();
        provider.putArray("allowedModelIds").add("gpt-5.6-sol").add("untrusted-model");
        Files.write(Path.of(properties.getCrossAiTrustRootFile()), canonicalizer.canonicalBytes(broadened));
        properties.setCrossAiTrustRootSha256(canonicalizer.digest(broadened));

        assertThatThrownBy(() -> store().probeReady())
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("provider-review key");
    }

    @Test
    void rejectsRuntimeKeyRouteSubstitutionEvenWithMatchingFilePin() throws Exception {
        ObjectNode substituted = runtimeRoot();
        ((ObjectNode) substituted.withArray("keys").get(0))
                .put("keyId", "vault-transit://endpoint-admin/foreign-runtime-attestor#v1");
        Files.write(Path.of(properties.getRuntimeTrustRootFile()), canonicalizer.canonicalBytes(substituted));
        properties.setRuntimeTrustRootSha256(new ViewOnlyDigest(canonicalizer).domainDigest(
                "faz22.6/view-only/runtime-trust-root/v1", "trustRoot", substituted));

        assertThatThrownBy(() -> store().probeReady())
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("runtime trust key");
    }

    @Test
    void readinessRejectsExpiredProviderAndFutureRuntimeSignerKeys() throws Exception {
        ObjectNode expiredProviderRoot = crossRoot();
        ObjectNode provider = (ObjectNode) java.util.stream.StreamSupport.stream(
                        expiredProviderRoot.withArray("keys").spliterator(), false)
                .filter(key -> "provider-review".equals(key.get("role").textValue()))
                .findFirst().orElseThrow();
        provider.put("notBefore", NOW.minusSeconds(7200).toString());
        provider.put("notAfter", NOW.minusSeconds(1).toString());
        Files.write(Path.of(properties.getCrossAiTrustRootFile()),
                canonicalizer.canonicalBytes(expiredProviderRoot));
        properties.setCrossAiTrustRootSha256(canonicalizer.digest(expiredProviderRoot));

        assertThatThrownBy(() -> store().probeReady())
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("currently active provider-review");

        ObjectNode activeCrossRoot = crossRoot();
        Files.write(Path.of(properties.getCrossAiTrustRootFile()), canonicalizer.canonicalBytes(activeCrossRoot));
        properties.setCrossAiTrustRootSha256(canonicalizer.digest(activeCrossRoot));
        ObjectNode futureRuntimeRoot = runtimeRoot();
        ObjectNode checkpoint = (ObjectNode) java.util.stream.StreamSupport.stream(
                        futureRuntimeRoot.withArray("keys").spliterator(), false)
                .filter(key -> "checkpoint-signer".equals(key.get("role").textValue()))
                .findFirst().orElseThrow();
        checkpoint.put("notBefore", NOW.plusSeconds(1).toString());
        checkpoint.put("notAfter", NOW.plusSeconds(7200).toString());
        Files.write(Path.of(properties.getRuntimeTrustRootFile()),
                canonicalizer.canonicalBytes(futureRuntimeRoot));
        properties.setRuntimeTrustRootSha256(new ViewOnlyDigest(canonicalizer).domainDigest(
                "faz22.6/view-only/runtime-trust-root/v1", "trustRoot", futureRuntimeRoot));

        assertThatThrownBy(() -> store().probeReady())
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("currently active checkpoint-signer");
    }

    @Test
    void exposesOnlyTheExactActiveRuntimeCheckpointSignerAuthority() {
        ViewOnlyPublicTrustStore.RuntimeSignerAuthority authority = store().checkpointSignerAuthority(
                "vault-transit://endpoint-admin/view-only-checkpoint#v1");

        assertThat(authority.keyId())
                .isEqualTo("vault-transit://endpoint-admin/view-only-checkpoint#v1");
        assertThat(authority.publicKeySha256()).matches("sha256:[0-9a-f]{64}");
        assertThat(authority.publicKey()).hasSize(32);
        assertThatThrownBy(() -> store().checkpointSignerAuthority(
                "vault-transit://endpoint-admin/foreign#v1"))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("active runtime checkpoint authority");
    }

    private ViewOnlyPublicTrustStore store() {
        return new ViewOnlyPublicTrustStore(
                canonicalizer, Clock.fixed(NOW, ZoneOffset.UTC), properties);
    }

    private ObjectNode crossRoot() {
        ObjectNode root = canonicalizer.mapper().createObjectNode();
        root.put("schemaVersion", "acik.cross-ai-deployment-trust-root.v2");
        root.put("trustRootId", "123e4567-e89b-42d3-a456-426614174011");
        root.put("sourcePublicKeysetSha256", "sha256:" + "1".repeat(64));
        root.put("issuedAt", NOW.minusSeconds(3600).toString());
        root.put("expiresAt", NOW.plusSeconds(3600).toString());
        root.put("maxClockSkewSeconds", 30);
        root.putArray("requiredProviderFamilies").add("openai");
        root.put("minimumProviderFamilies", 1);
        root.put("minimumDirectProviderRoutes", 1);
        ArrayNode keys = root.putArray("keys");
        crossKeys.forEach((role, pair) -> {
            ObjectNode key = keys.addObject();
            key.put("keyId", keyId(role));
            key.put("role", role);
            key.put("publicKeyBase64", Base64.getEncoder().encodeToString(rawPublicKey(pair)));
            key.put("notBefore", NOW.minusSeconds(3600).toString());
            key.put("notAfter", NOW.plusSeconds(3600).toString());
            if ("provider-review".equals(role)) {
                key.put("providerFamily", "openai");
                key.putArray("allowedChannels").add("openai-codex");
                key.putArray("allowedModelIds")
                        .add("gpt-5.3-codex-spark")
                        .add("gpt-5.6-sol");
                key.putArray("allowedModelIdentityClasses").add("trusted-launch-attested");
                key.put("directProviderCli", true);
            } else {
                key.putNull("providerFamily");
                key.putArray("allowedChannels");
                key.putArray("allowedModelIds");
                key.putArray("allowedModelIdentityClasses");
                key.putNull("directProviderCli");
            }
        });
        return root;
    }

    private ObjectNode runtimeRoot() {
        ObjectNode root = canonicalizer.mapper().createObjectNode();
        root.put("schemaVersion", "faz22.6.viewOnlyRuntimeTrustRoot.v1");
        root.put("activationState", "active");
        root.put("trustRootId", "faz22-view-only-runtime-test-v1");
        root.put("digestDomain", "faz22.6/view-only/runtime-trust-root/v1");
        root.put("algorithm", "ed25519");
        ArrayNode keys = root.putArray("keys");
        runtimeKeys.forEach((role, pair) -> {
            ObjectNode key = keys.addObject();
            key.put("keyId", keyId(role));
            key.put("role", role);
            key.put("version", 1);
            key.put("publicKeyBase64", Base64.getEncoder().encodeToString(rawPublicKey(pair)));
            key.put("notBefore", NOW.minusSeconds(3600).toString());
            key.put("notAfter", NOW.plusSeconds(3600).toString());
            key.put("state", "active");
        });
        root.putArray("revocations");
        root.put("generatedAt", NOW.minusSeconds(60).toString());
        return root;
    }

    private ObjectNode envelope(String payloadType, JsonNode payload, String role, KeyPair pair) {
        try {
            byte[] payloadBytes = canonicalizer.canonicalBytes(payload);
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(ViewOnlyDsseSigner.pae(payloadType, payloadBytes));
            ObjectNode envelope = canonicalizer.mapper().createObjectNode();
            envelope.put("payloadType", payloadType);
            envelope.put("payload", Base64.getEncoder().encodeToString(payloadBytes));
            ObjectNode signature = envelope.putArray("signatures").addObject();
            signature.put("keyid", keyId(role));
            signature.put("sig", Base64.getEncoder().encodeToString(signer.sign()));
            return envelope;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Map<String, KeyPair> keys(String... roles) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        Map<String, KeyPair> result = new LinkedHashMap<>();
        for (String role : roles) {
            result.put(role, generator.generateKeyPair());
        }
        return result;
    }

    private static byte[] rawPublicKey(KeyPair pair) {
        byte[] encoded = pair.getPublic().getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    private static String keyId(String role) {
        if ("runtime-attestor".equals(role)) {
            return "vault-transit://endpoint-admin/view-only-runtime-attestor#v1";
        }
        if ("checkpoint-signer".equals(role)) {
            return "vault-transit://endpoint-admin/view-only-checkpoint#v1";
        }
        if ("provider-review".equals(role)) {
            return "vault-transit://cross-ai/openai#v1";
        }
        return "vault-transit://cross-ai/" + role + "#v1";
    }
}
