package com.serban.notify.adapter.webpush;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DefaultWebPushSender unit test (Faz 23.7 M7 T4.2 PR-W2.3).
 *
 * <p>Library wiring + BouncyCastle provider registration verification.
 * WireMock IT real HTTP integration (lib send call end-to-end) PR-W2.4
 * scope — bu seans'ta library Apache HC 4.x ile internal HTTP yapıyor;
 * WireMock + provider mock pattern ayrı setup gerek.
 */
class DefaultWebPushSenderTest {

    private static String validPublicKey;
    private static String validPrivateKey;

    @BeforeAll
    static void generateRealKeyPair() throws Exception {
        // BouncyCastle ECDSA P-256 key pair generation (lib gerçek curve
        // point validation yapıyor; fake bytes IllegalArgumentException).
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();

        ECPublicKey pubKey = (ECPublicKey) kp.getPublic();
        ECPrivateKey privKey = (ECPrivateKey) kp.getPrivate();

        // Encode uncompressed P-256: 0x04 + X (32-byte) + Y (32-byte)
        byte[] pubBytes = new byte[65];
        pubBytes[0] = 0x04;
        byte[] x = bigIntegerToBytes(pubKey.getW().getAffineX(), 32);
        byte[] y = bigIntegerToBytes(pubKey.getW().getAffineY(), 32);
        System.arraycopy(x, 0, pubBytes, 1, 32);
        System.arraycopy(y, 0, pubBytes, 33, 32);

        byte[] privBytes = bigIntegerToBytes(privKey.getS(), 32);

        validPublicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes);
        validPrivateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(privBytes);
    }

    private static byte[] bigIntegerToBytes(java.math.BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) return raw;
        byte[] result = new byte[length];
        if (raw.length > length) {
            // Strip leading sign byte (0x00) for positive BigInteger
            System.arraycopy(raw, raw.length - length, result, 0, length);
        } else {
            // Left-pad with zeros
            System.arraycopy(raw, 0, result, length - raw.length, raw.length);
        }
        return result;
    }

    @Test
    void bouncyCastleProviderRegisteredOnInit() throws Exception {
        DefaultWebPushSender sender = new DefaultWebPushSender(validConfig());

        assertThat(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)).isNotNull();
        assertThat(sender.getMaxPlaintextBytes()).isEqualTo(3072);
        assertThat(sender.getDefaultTtlSeconds()).isEqualTo(3600);
    }

    @Test
    void invalidPublicKeyFailsConstructor() {
        // PushService library validates VAPID key on construction;
        // blank/malformed key → GeneralSecurityException
        WebPushConfig config = new WebPushConfig(
            true,                  // enabled
            "",                    // blank publicKey
            validPrivateKey,
            "mailto:admin@example.com",
            10, 3600, "none", 3072
        );

        assertThatThrownBy(() -> new DefaultWebPushSender(config))
            .isInstanceOf(Exception.class);
    }

    @Test
    void validKeysParseSuccessfully() throws Exception {
        DefaultWebPushSender sender = new DefaultWebPushSender(validConfig());

        // Sender ready; lib internal PushService initialized
        assertThat(sender.getDefaultTtlSeconds()).isEqualTo(3600);
    }

    private WebPushConfig validConfig() {
        return new WebPushConfig(
            true,
            validPublicKey,   // real BouncyCastle-generated P-256 pubkey
            validPrivateKey,  // real BouncyCastle-generated P-256 privkey
            "mailto:admin@example.com",
            10, 3600, "none", 3072
        );
    }
}
