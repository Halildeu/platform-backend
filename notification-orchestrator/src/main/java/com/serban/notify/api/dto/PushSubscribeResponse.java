package com.serban.notify.api.dto;

import java.util.UUID;

/**
 * Push subscription registration response (Faz 23.7 M7 T4.2 PR-W3).
 *
 * @param endpointId  endpoint UUID — DELETE /api/v1/notify/push/subscribe/{endpointId} için kullanılır
 * @param status      "created" (yeni satır) | "updated" (mevcut endpoint
 *                    keys güncellendi) | "reactivated" (soft-deleted satır
 *                    yeniden aktif)
 */
public record PushSubscribeResponse(
    UUID endpointId,
    String status
) {}
