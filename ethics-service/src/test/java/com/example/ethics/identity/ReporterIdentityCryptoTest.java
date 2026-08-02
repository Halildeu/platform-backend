package com.example.ethics.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ethics.config.ReporterIdentityProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ES-212 — the envelope's guarantees, stated as tests rather than as comments. */
class ReporterIdentityCryptoTest {

    private static final String KEY_V1 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final UUID CASE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CASE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static ReporterIdentityCrypto crypto(String activeKeyId, Map<String, String> keys) {
        ReporterIdentityProperties properties = new ReporterIdentityProperties();
        properties.setActiveKeyId(activeKeyId);
        properties.setKeys(keys);
        return new ReporterIdentityCrypto(properties);
    }

    @Test
    void roundTripsThroughTheActiveKey() {
        ReporterIdentityCrypto crypto = crypto("v1", Map.of("v1", KEY_V1));
        ReporterIdentityCrypto.Sealed sealed = crypto.seal(CASE, "{\"fullName\":\"Ayşe Yılmaz\"}");
        assertEquals("v1", sealed.keyId());
        assertEquals("{\"fullName\":\"Ayşe Yılmaz\"}",
                crypto.open(CASE, sealed.keyId(), sealed.nonce(), sealed.ciphertext()));
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        ReporterIdentityCrypto crypto = crypto("v1", Map.of("v1", KEY_V1));
        ReporterIdentityCrypto.Sealed sealed = crypto.seal(CASE, "{\"fullName\":\"Ayşe Yılmaz\"}");
        String asLatin1 = new String(sealed.ciphertext(), StandardCharsets.ISO_8859_1);
        assertFalse(asLatin1.contains("Ayşe"), "the name survived encryption in readable form");
        assertFalse(asLatin1.contains("fullName"), "the payload structure survived encryption");
    }

    @Test
    void aCiphertextMovedToAnotherCaseDoesNotOpen() {
        // The case id is bound in as additional authenticated data precisely so that
        // someone with write access to the table cannot re-point an identity at a
        // different case and have the read path hand back the wrong person's name.
        ReporterIdentityCrypto crypto = crypto("v1", Map.of("v1", KEY_V1));
        ReporterIdentityCrypto.Sealed sealed = crypto.seal(CASE, "{\"fullName\":\"Ayşe Yılmaz\"}");
        assertThrows(IllegalStateException.class,
                () -> crypto.open(OTHER_CASE, sealed.keyId(), sealed.nonce(), sealed.ciphertext()));
    }

    @Test
    void retiredKeysStayReadableAfterRotation() {
        ReporterIdentityCrypto before = crypto("v1", Map.of("v1", KEY_V1));
        ReporterIdentityCrypto.Sealed sealed = before.seal(CASE, "{\"fullName\":\"Ayşe Yılmaz\"}");

        String keyV2 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        ReporterIdentityCrypto after = crypto("v2", Map.of("v1", KEY_V1, "v2", keyV2));

        assertEquals("v2", after.activeKeyId(), "new rows must seal under the new key");
        assertEquals("{\"fullName\":\"Ayşe Yılmaz\"}",
                after.open(CASE, sealed.keyId(), sealed.nonce(), sealed.ciphertext()),
                "rotation must not orphan rows written under the previous key");
    }

    @Test
    void isNotOperationalWithoutUsableKeyMaterial() {
        assertFalse(crypto("", Map.of()).isOperational(), "no active key id");
        assertFalse(crypto("v1", Map.of()).isOperational(), "active id names a key that is absent");
        assertFalse(crypto("v1", Map.of("v1", "")).isOperational(), "blank material");
        assertFalse(crypto("v1", Map.of("v1", "not-base64-!!")).isOperational(), "undecodable material");
        // AES would happily accept 128- and 192-bit keys, so a truncated or half-pasted
        // secret would "work" while silently weakening every identity sealed under it.
        assertFalse(crypto("v1", Map.of("v1", Base64.getEncoder().encodeToString(new byte[16]))).isOperational(),
                "a 128-bit key must be refused, not quietly accepted");
        assertTrue(crypto("v1", Map.of("v1", KEY_V1)).isOperational());
    }
}
