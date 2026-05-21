package com.serban.notify.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Push subscription registration request (Faz 23.7 M7 T4.2 PR-W3).
 *
 * <p>Browser-side {@code PushManager.subscribe()} sonucu döner. Service
 * worker registration ile birlikte browser endpoint URL + ECDH public key
 * (p256dh) + auth secret üretir; bu DTO ile backend'e iletilir.
 *
 * <p>Field validation:
 * <ul>
 *   <li>{@code endpointUrl} — push service URL (FCM/Mozilla/Edge);
 *       https şart, 2048 char max (DB column constraint)</li>
 *   <li>{@code p256dhKey} — base64url-encoded P-256 public key
 *       (65-byte uncompressed; encoded uzunluk 88 char), 512 char max</li>
 *   <li>{@code authSecret} — base64url-encoded 16-byte auth secret
 *       (encoded uzunluk 22 char), 256 char max</li>
 *   <li>{@code userAgent} — optional browser UA hint (audit/debug);
 *       512 char max</li>
 * </ul>
 *
 * @param endpointUrl push service POST URL (FCM/Mozilla/Edge endpoint)
 * @param p256dhKey   browser subscription public key (base64url)
 * @param authSecret  browser subscription auth secret (base64url)
 * @param userAgent   optional UA hint (browser/OS audit)
 */
public record PushSubscribeRequest(
    @NotBlank
    @Pattern(regexp = "^https://.+",
        message = "endpointUrl must be https push service URL")
    @Size(max = 2048)
    String endpointUrl,

    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_-]+$",
        message = "p256dhKey must be base64url encoded (no padding)")
    @Size(max = 512)
    String p256dhKey,

    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9_-]+$",
        message = "authSecret must be base64url encoded (no padding)")
    @Size(max = 256)
    String authSecret,

    @Size(max = 512)
    String userAgent
) {}
