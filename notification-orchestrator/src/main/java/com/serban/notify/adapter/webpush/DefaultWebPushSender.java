package com.serban.notify.adapter.webpush;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.Security;

/**
 * DefaultWebPushSender — nl.martijndwars:web-push library integration
 * (Faz 23.7 M7 T4.2 PR-W2.3 — Codex {@code 019e49e7} Opsiyon A absorb).
 *
 * <p>Library wires:
 * <ul>
 *   <li>{@link PushService} (publicKey + privateKey + subject) —
 *       VAPID JWT signing handles per-request</li>
 *   <li>{@link Notification} (endpoint + p256dh + authSecret + payload
 *       + ttl) — ECDH + AES-128-GCM payload encryption handles</li>
 *   <li>{@code send(Notification)} → Apache HC 4.x HttpResponse</li>
 * </ul>
 *
 * <p>BouncyCastle JCA provider registration burada (lib gereksiniminden);
 * static init.
 *
 * <p>Codex P3 absorb: per-request JWT (no cache); PushService internal
 * VAPID builder her send'de yeni JWT yapar — aud endpoint URL origin'inden
 * türetilir, exp~12h, sub config.vapidSubject().
 *
 * <p>Exception mapping (adapter caller'da):
 * <ul>
 *   <li>GeneralSecurityException / JoseException → RETRY (transient
 *       crypto/jwt issue)</li>
 *   <li>IOException → RETRY (network)</li>
 *   <li>InterruptedException → RETRY (thread interrupt)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
    name = "notify.adapters.webpush.enabled",
    havingValue = "true"
)
public class DefaultWebPushSender implements WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(DefaultWebPushSender.class);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final WebPushConfig config;
    private final PushService pushService;

    public DefaultWebPushSender(WebPushConfig config) throws GeneralSecurityException {
        this.config = config;
        // Library decodes raw base64url 65-byte uncompressed P-256 public
        // (0x04 prefix) + 32-byte private scalar internally via Utils.
        this.pushService = new PushService(
            config.publicKey(),
            config.privateKey(),
            config.vapidSubject()
        );
        log.info("DefaultWebPushSender activated: subject={} ttl={}s",
            config.vapidSubject(), config.defaultTtlSeconds());
    }

    @Override
    public SendResult send(
        String endpointUrl,
        String p256dhKey,
        String authSecret,
        byte[] payload,
        int ttlSeconds
    ) throws Exception {
        // Notification 5-arg constructor (base64url string keys overload):
        // (endpoint, userPublicKey, userAuth, payload, ttl)
        Notification notification = new Notification(
            endpointUrl,
            p256dhKey,
            authSecret,
            payload,
            ttlSeconds
        );
        var response = pushService.send(notification);
        int statusCode = response.getStatusLine().getStatusCode();
        String reason = response.getStatusLine().getReasonPhrase();

        log.debug("webpush send: status={} reason={}", statusCode, reason);
        return new SendResult(statusCode, reason);
    }

    @Override
    public int getMaxPlaintextBytes() {
        return config.maxPlaintextBytes();
    }

    @Override
    public int getDefaultTtlSeconds() {
        return config.defaultTtlSeconds();
    }
}
