package com.serban.notify.adapter.webpush;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultWebPushSender HTTP integration test (Faz 23.7 M7 T4.2 PR-W2.4 —
 * Codex {@code 019e49e7} P4 + {@code 019e4a2e} P1+P2 absorb).
 *
 * <p>Codex {@code 019e4a2e} P1 absorb: sınıf adı Surefire default
 * {@code **&#47;*Test.java} include pattern'i için "Test" suffix'i ile
 * bitirildi (önceki ad {@code DefaultWebPushSenderIT.java} default
 * lane'de skip ediliyordu — `*IT.java` Failsafe gerektirir).
 *
 * <p>End-to-end HTTP integration: lib (nl.martijndwars:web-push 5.1.1)
 * Apache HC 4.x internal client real POST to WireMock-stubbed push
 * service endpoint. VAPID JWT signing + ECDH payload encryption pipeline
 * lib tarafında çalışır; WireMock sadece push service response code
 * (201 / 204 / 410 / 429) simüle eder.
 *
 * <p>Verifies:
 * <ul>
 *   <li>201 Created → SendResult.statusCode=201</li>
 *   <li>204 No Content → SendResult.statusCode=204</li>
 *   <li>410 Gone → SendResult.statusCode=410 (subscription expired)</li>
 *   <li>429 Too Many Requests → SendResult.statusCode=429 (rate limit)</li>
 *   <li>VAPID Authorization header {@code WebPush <jwt>} (RFC 8292
 *       draft-04 öncesi format; nl.martijndwars 5.1.1 default)</li>
 *   <li>TTL header present (RFC 8030)</li>
 *   <li>Content-Encoding header exact {@code aesgcm} (RFC 8291 draft-04
 *       öncesi format; lib 5.1.1 final {@code aes128gcm} desteklemiyor —
 *       Codex P2 absorb: contract-lock için strict assertion)</li>
 * </ul>
 *
 * <p>Test fixture:
 * <ul>
 *   <li>BouncyCastle ECDSA P-256 key pair (VAPID) — gerçek curve point</li>
 *   <li>Subscription EC key pair (browser-side simülasyonu) — gerçek
 *       curve point, lib ECDH encryption için zorunlu</li>
 *   <li>Auth secret — 16 random byte (browser PushSubscription'da
 *       browser üretir)</li>
 * </ul>
 */
class DefaultWebPushSenderHttpIntegrationTest {

    @RegisterExtension
    static WireMockExtension pushService = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private static String vapidPublicKey;
    private static String vapidPrivateKey;
    private static String subscriptionP256dh;
    private static String subscriptionAuthSecret;

    @BeforeAll
    static void generateKeyMaterial() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        // VAPID key pair (server identity)
        KeyPair vapidKp = generateP256KeyPair();
        ECPublicKey vapidPub = (ECPublicKey) vapidKp.getPublic();
        ECPrivateKey vapidPriv = (ECPrivateKey) vapidKp.getPrivate();
        vapidPublicKey = encodePublicKeyUncompressed(vapidPub);
        vapidPrivateKey = encodePrivateKey(vapidPriv);

        // Subscription public key (browser-side simülasyonu; lib ECDH
        // encryption pipeline için gerçek curve point zorunlu)
        KeyPair subKp = generateP256KeyPair();
        ECPublicKey subPub = (ECPublicKey) subKp.getPublic();
        subscriptionP256dh = encodePublicKeyUncompressed(subPub);

        // Auth secret — 16 random byte (browser PushSubscription auth)
        byte[] authBytes = new byte[16];
        SecureRandom.getInstanceStrong().nextBytes(authBytes);
        subscriptionAuthSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(authBytes);
    }

    private static KeyPair generateP256KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    private static String encodePublicKeyUncompressed(ECPublicKey pub) {
        byte[] pubBytes = new byte[65];
        pubBytes[0] = 0x04;
        byte[] x = bigIntegerToBytes(pub.getW().getAffineX(), 32);
        byte[] y = bigIntegerToBytes(pub.getW().getAffineY(), 32);
        System.arraycopy(x, 0, pubBytes, 1, 32);
        System.arraycopy(y, 0, pubBytes, 33, 32);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes);
    }

    private static String encodePrivateKey(ECPrivateKey priv) {
        byte[] privBytes = bigIntegerToBytes(priv.getS(), 32);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(privBytes);
    }

    private static byte[] bigIntegerToBytes(java.math.BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        if (raw.length == length) return raw;
        byte[] result = new byte[length];
        if (raw.length > length) {
            System.arraycopy(raw, raw.length - length, result, 0, length);
        } else {
            System.arraycopy(raw, 0, result, length - raw.length, raw.length);
        }
        return result;
    }

    private DefaultWebPushSender newSender() throws Exception {
        WebPushConfig config = new WebPushConfig(
            true,
            vapidPublicKey,
            vapidPrivateKey,
            "mailto:admin@example.com",
            10, 3600, "none", 3072
        );
        return new DefaultWebPushSender(config);
    }

    // Codex 019e4a2e P1 absorb: urlPathMatching catch-all yerine her
    // test kendi exact endpoint'inde stub yapar. Method-level parallel
    // execution açılırsa shared WireMock üzerinde race güvenliği artar.

    @Test
    void push201CreatedReturnsStatusCode201() throws Exception {
        pushService.stubFor(post(urlEqualTo("/push/abc123"))
            .willReturn(aResponse().withStatus(201)));

        DefaultWebPushSender sender = newSender();
        byte[] payload = "{\"title\":\"Hello\",\"body\":\"WireMock IT\"}".getBytes(StandardCharsets.UTF_8);

        WebPushSender.SendResult result = sender.send(
            pushService.url("/push/abc123"),
            subscriptionP256dh,
            subscriptionAuthSecret,
            payload,
            3600
        );

        assertThat(result.statusCode()).isEqualTo(201);

        // VAPID + TTL + Content-Encoding header'ları lib tarafından
        // RFC 8030 + RFC 8292 uyumunda set edilir. nl.martijndwars:web-push
        // 5.1.1 eski draft formatını kullanıyor: "WebPush <jwt>" +
        // aesgcm (RFC 8291 draft-04 öncesi). Codex 019e4a2e P2 absorb:
        // contract lock için Content-Encoding exact "aesgcm" assertion
        // (lib 5.1.1 final aes128gcm desteklemiyor).
        pushService.verify(postRequestedFor(urlEqualTo("/push/abc123"))
            .withHeader("Authorization", matching("WebPush .+"))
            .withHeader("TTL", matching("\\d+"))
            .withHeader("Content-Encoding", equalTo("aesgcm")));
    }

    @Test
    void push204NoContentReturnsStatusCode204() throws Exception {
        pushService.stubFor(post(urlEqualTo("/push/edge-204"))
            .willReturn(aResponse().withStatus(204)));

        DefaultWebPushSender sender = newSender();
        byte[] payload = "{\"title\":\"X\"}".getBytes(StandardCharsets.UTF_8);

        WebPushSender.SendResult result = sender.send(
            pushService.url("/push/edge-204"),
            subscriptionP256dh,
            subscriptionAuthSecret,
            payload,
            3600
        );

        assertThat(result.statusCode()).isEqualTo(204);
    }

    @Test
    void push410GoneReturnsStatusCode410() throws Exception {
        // RFC 8030: 410 = subscription expired (browser uninstalled SW or
        // permission revoked). Adapter mapper bu kodu FAILED +
        // subscription_expired + endpoint soft-delete'e mapler.
        pushService.stubFor(post(urlEqualTo("/push/expired"))
            .willReturn(aResponse().withStatus(410).withBody("Gone")));

        DefaultWebPushSender sender = newSender();
        byte[] payload = "{\"title\":\"X\"}".getBytes(StandardCharsets.UTF_8);

        WebPushSender.SendResult result = sender.send(
            pushService.url("/push/expired"),
            subscriptionP256dh,
            subscriptionAuthSecret,
            payload,
            3600
        );

        assertThat(result.statusCode()).isEqualTo(410);
    }

    @Test
    void push429TooManyRequestsReturnsStatusCode429() throws Exception {
        // Push service rate limit; adapter mapper bu kodu RETRY'a mapler
        // (PR4 worker backoff).
        pushService.stubFor(post(urlEqualTo("/push/throttled"))
            .willReturn(aResponse().withStatus(429).withBody("Too Many Requests")));

        DefaultWebPushSender sender = newSender();
        byte[] payload = "{\"title\":\"X\"}".getBytes(StandardCharsets.UTF_8);

        WebPushSender.SendResult result = sender.send(
            pushService.url("/push/throttled"),
            subscriptionP256dh,
            subscriptionAuthSecret,
            payload,
            3600
        );

        assertThat(result.statusCode()).isEqualTo(429);
    }
}
