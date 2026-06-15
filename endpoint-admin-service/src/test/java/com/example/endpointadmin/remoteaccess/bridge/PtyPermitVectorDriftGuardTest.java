package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.CanonicalCommand;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 CONSTRAINED_PTY slice-1b (Codex 019ecbc5) — the JAVA side of the cross-language permit test vector.
 *
 * <p>The agent (platform-agent {@code internal/remotebridge/operation}) verifies the SAME committed vector
 * (byte-exact canonical + signature + commandHash). This test is the drift-guard: it asserts the broker side
 * still produces the committed canonical bytes and that the committed signature still verifies under the
 * committed public key — so neither side's canonical serialization can drift one-sided without a red test.
 * The fixture is the single source of truth (committed identically in both repos); Java is authoritative
 * (broker-private), so the broker MUST stay byte-stable against it.
 */
class PtyPermitVectorDriftGuardTest {

    private static final long FRESH_NOW = 1100L; // inside [issuedAt=1000, expiresAt=1300)

    @Test
    void brokerStillProducesTheCommittedCanonicalBytesAndTheCommittedSignatureStillVerifies() throws Exception {
        JsonNode v;
        try (InputStream in = getClass().getResourceAsStream("/remote-bridge/pty-permit-vector.json")) {
            assertNotNull(in, "committed cross-language permit vector must be on the test classpath");
            v = new ObjectMapper().readTree(in);
        }

        OperationPermit permit = new OperationPermit(
                v.get("alg").asText(), v.get("kid").asText(), v.get("permitVersion").asInt(),
                v.get("policyVersion").asText(), v.get("decisionId").asText(), v.get("sessionId").asText(),
                v.get("operationId").asText(), v.get("deviceId").asText(), v.get("operatorSubject").asText(),
                RemoteSessionCapability.valueOf(v.get("capability").asText()), v.get("commandHash").asText(),
                v.get("issuedAtEpochMillis").asLong(), v.get("expiresAtEpochMillis").asLong(),
                v.get("seq").asLong(), v.get("signatureB64").asText());

        // (1) the broker's canonical bytes are byte-exact with the committed vector (no Java-side drift).
        assertEquals(v.get("canonicalPayloadHex").asText(), toHex(permit.canonicalPayload()),
                "broker canonicalPayload() drifted from the committed cross-language vector");

        // (2) the committed signature still verifies under the committed public key (broker verifier agrees).
        PublicKey brokerPub = parseSpki(v.get("brokerPublicKeyB64").asText());
        RemoteBridgePermitVerifier verifier = new RemoteBridgePermitVerifier(brokerPub, v.get("kid").asText());
        assertTrue(verifier.verify(permit, FRESH_NOW), "committed signature must verify under the committed key");
        // expiry boundary is enforced (the agent mirrors this)
        assertFalse(verifier.verify(permit, v.get("expiresAtEpochMillis").asLong()), "must reject at expiry");

        // (3) the command hash is byte-stable with the committed vector.
        assertEquals(v.get("commandHash").asText(), CanonicalCommand.of(v.get("commandLine").asText()).hash(),
                "broker CanonicalCommand.hash() drifted from the committed cross-language vector");
    }

    private static PublicKey parseSpki(String b64) throws Exception {
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
