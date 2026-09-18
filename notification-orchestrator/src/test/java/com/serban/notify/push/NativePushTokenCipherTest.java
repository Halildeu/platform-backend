package com.serban.notify.push;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class NativePushTokenCipherTest {
    private final NativePushTokenCipher cipher = new NativePushTokenCipher(new byte[32]);

    @Test void roundTripUsesFreshNonce() {
        String first = cipher.encrypt("synthetic-token", "registration-A");
        String second = cipher.encrypt("synthetic-token", "registration-A");
        assertNotEquals(first, second);
        assertEquals("synthetic-token", cipher.decrypt(first, "registration-A"));
        assertFalse(first.contains("synthetic-token"));
    }

    @Test void anotherRegistrationCannotReadToken() {
        String envelope = cipher.encrypt("synthetic-token", "registration-A");
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(envelope, "registration-B"));
    }

    @Test void tamperingFailsWithoutTokenInError() {
        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("synthetic-token", "registration-A"));
        bytes[bytes.length - 1] ^= 1;
        var failure = assertThrows(IllegalStateException.class,
            () -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes), "registration-A"));
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("synthetic-token"));
    }

    @Test void rejectsMissingKeyIdentityAndMalformedEnvelope() {
        assertThrows(IllegalArgumentException.class, () -> new NativePushTokenCipher(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt("token", ""));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("not-base64", "registration-A"));
    }
}
