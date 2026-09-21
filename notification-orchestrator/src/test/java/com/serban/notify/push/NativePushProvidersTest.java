package com.serban.notify.push;

import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Map;
import com.serban.notify.adapter.ChannelAdapter.DeliveryAttemptResult;
import static org.junit.jupiter.api.Assertions.*;

class NativePushProvidersTest {
    @Test void apnsJwtIsSignedAndReusedWithinRefreshWindow() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        var supplier = NativePushProviders.apnsToken((ECPrivateKey) pair.getPrivate(), "ABCDEFGHIJ", "0123456789");
        var jwt = SignedJWT.parse(supplier.get());
        assertTrue(jwt.verify(new ECDSAVerifier((ECPublicKey) pair.getPublic())));
        assertEquals("0123456789", jwt.getJWTClaimsSet().getIssuer());
        assertEquals("ABCDEFGHIJ", jwt.getHeader().getKeyID());
        assertEquals(jwt.serialize(), supplier.get());
    }
    @Test void retryAfterCannotShortenBackoffOrAffectOtherProviders() {
        var result = new DeliveryAttemptResult(DeliveryAttemptResult.Status.RETRY, null, "native_provider_retry", 429,
            null, Map.of("retryAfterSeconds", 120));
        assertEquals(Duration.ofSeconds(120), NativePushRetry.delay(Duration.ofSeconds(5), result));
        assertEquals(Duration.ofSeconds(300), NativePushRetry.delay(Duration.ofSeconds(300), result));
        assertEquals(Duration.ofSeconds(5), NativePushRetry.delay(Duration.ofSeconds(5), DeliveryAttemptResult.retry("other", 429)));
    }
}
