package com.serban.notify.push;

import com.serban.notify.api.dto.PushSubscribeRequest;
import com.serban.notify.exception.InvalidRequestException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;

/**
 * Web Push subscription material validator
 * (Faz 23.7 M7 T4.2 PR-W3 iter-2 — Codex {@code 019e4a57} P2 absorb).
 *
 * <p>DTO syntax pattern (https + base64url charset) tek başına yeterli
 * değil — bozuk key {@code DefaultWebPushSender} send aşamasında patlar.
 * Plan-time validation şu kontratları enforce eder:
 *
 * <ul>
 *   <li>{@code endpointUrl}: URI parse + scheme=https + host non-blank
 *       (Pattern {@code ^https://.+} sadece prefix; gerçek URI parse
 *       browser-side {@code PushManager.subscribe} çıktısıyla hizalı)</li>
 *   <li>{@code p256dhKey}: base64url decode → 65 byte uncompressed P-256
 *       (ilk byte {@code 0x04} + 32 byte X + 32 byte Y). RFC 8291 + W3C
 *       Push API specification.</li>
 *   <li>{@code authSecret}: base64url decode → 16 byte (Push API
 *       {@code PushSubscription.getKey('auth')} uzunluğu).</li>
 * </ul>
 *
 * <p>Validation runtime'da çağrılır (service-side); bozuk material'i
 * {@code InvalidRequestException} ile 400 surface'e çevirir. Bean
 * Validation custom annotation alternatifi daha ceremoniose ve test
 * surface'i daha pahalı; tek dosya helper tercih edildi.
 */
public final class PushSubscriptionMaterialValidator {

    private static final int P256_UNCOMPRESSED_LENGTH = 65;
    private static final byte P256_UNCOMPRESSED_PREFIX = 0x04;
    private static final int AUTH_SECRET_LENGTH = 16;

    private PushSubscriptionMaterialValidator() {
        // static utility
    }

    /**
     * Validate subscription material (URL + keys). Throws on first violation
     * with a non-PII safe message.
     */
    public static void validate(PushSubscribeRequest request) {
        validateEndpointUrl(request.endpointUrl());
        validateP256dhKey(request.p256dhKey());
        validateAuthSecret(request.authSecret());
    }

    private static void validateEndpointUrl(String endpointUrl) {
        URI uri;
        try {
            uri = new URI(endpointUrl);
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("endpointUrl: invalid URI syntax");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new InvalidRequestException("endpointUrl: scheme must be https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidRequestException("endpointUrl: host required");
        }
    }

    private static void validateP256dhKey(String p256dhKey) {
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(p256dhKey);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("p256dhKey: invalid base64url encoding");
        }
        if (decoded.length != P256_UNCOMPRESSED_LENGTH) {
            throw new InvalidRequestException(
                "p256dhKey: decoded length must be " + P256_UNCOMPRESSED_LENGTH
                    + " bytes (uncompressed P-256), got " + decoded.length
            );
        }
        if (decoded[0] != P256_UNCOMPRESSED_PREFIX) {
            throw new InvalidRequestException(
                "p256dhKey: first byte must be 0x04 (uncompressed P-256 prefix)"
            );
        }
    }

    private static void validateAuthSecret(String authSecret) {
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(authSecret);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("authSecret: invalid base64url encoding");
        }
        if (decoded.length != AUTH_SECRET_LENGTH) {
            throw new InvalidRequestException(
                "authSecret: decoded length must be " + AUTH_SECRET_LENGTH
                    + " bytes, got " + decoded.length
            );
        }
    }
}
