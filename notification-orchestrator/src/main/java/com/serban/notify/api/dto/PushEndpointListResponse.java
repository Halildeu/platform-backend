package com.serban.notify.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Push endpoint list response (Faz 23.7 M7 T4.2 PR-W3).
 *
 * <p>GET /api/v1/notify/push/subscribe/me — subscriber'ın aktif endpoint
 * kayıtlarını döner. Raw {@code endpointUrl} (FCM/Mozilla/Edge URL),
 * {@code p256dhKey} ve {@code authSecret} response'da YOK (KVKK Madde 12
 * data minimization + push service URL endpoint token gibi davranır).
 *
 * <p>Browser tarafı bu liste ile mevcut subscription'ı eşleyip "şu cihaz
 * subscribe" UI flag'ini gösterir; raw endpoint'e gerek yok.
 *
 * @param endpoints aktif endpoint metadata listesi
 */
public record PushEndpointListResponse(
    List<Endpoint> endpoints
) {
    /**
     * Endpoint metadata — PII-minimal projection.
     *
     * @param endpointId    UUID — DELETE için kullanılır
     * @param userAgent     opsiyonel UA hint (audit/UX cihaz tanımlama)
     * @param platformHint  opsiyonel platform string (Chrome/Firefox/Edge)
     * @param createdAt     ilk subscribe zamanı
     * @param lastSeenAt    son DLR/heartbeat zamanı
     */
    public record Endpoint(
        UUID endpointId,
        String userAgent,
        String platformHint,
        OffsetDateTime createdAt,
        OffsetDateTime lastSeenAt
    ) {}
}
