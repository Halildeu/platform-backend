package com.serban.notify.api;

import com.serban.notify.api.dto.PushEndpointListResponse;
import com.serban.notify.api.dto.PushSubscribeRequest;
import com.serban.notify.api.dto.PushSubscribeResponse;
import com.serban.notify.push.PushSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * PushSubscriptionController — Web Push Protocol RFC 8030 self-service
 * subscription endpoints (Faz 23.7 M7 T4.2 PR-W3).
 *
 * <p>Browser-side flow:
 * <ol>
 *   <li>Service worker registration + Notification permission grant</li>
 *   <li>{@code PushManager.subscribe()} → browser endpoint URL + p256dh +
 *       auth secret üretir</li>
 *   <li>{@code POST /api/v1/notify/push/subscribe} → backend'e iletilir,
 *       idempotent upsert. {@link PushSubscribeResponse#endpointId()} UUID
 *       browser tarafında saklanır (cihaz kaybı sonrası re-subscribe ile
 *       senkronize edilir).</li>
 *   <li>{@code GET /api/v1/notify/push/subscribe/me} → aktif endpoint
 *       listesi (raw URL/keys hariç).</li>
 *   <li>{@code DELETE /api/v1/notify/push/subscribe/{endpointId}} → user
 *       açık tıkıyla unsubscribe. Endpoint-level soft-delete; multi-endpoint
 *       subscriber için diğer cihazlar etkilenmez.</li>
 * </ol>
 *
 * <p>Identity model: caller {@code X-Org-Id} + {@code X-Subscriber-Id}
 * headers; subject claim (JWT) {@code sub} = subscriberId match
 * {@link SubscriberIdentityGuard#requireMatchOrThrow} tarafından
 * doğrulanır. Cross-tenant + cross-subscriber endpoint isolation
 * {@link PushSubscriptionService#unsubscribe} içinde tenancy guard ile
 * korunur.
 *
 * <p>KVKK boundary (Codex {@code 019e49e7} P8): raw {@code endpointUrl},
 * {@code p256dhKey}, {@code authSecret} response body'sinde YOK; sadece
 * endpoint UUID + browser-side audit metadata (userAgent, platformHint,
 * timestamps) döner. Push service URL endpoint token gibi davranır.
 */
@RestController
@RequestMapping("/api/v1/notify/push/subscribe")
@Validated
@Tag(name = "Push — Web Push Subscription",
     description = "Browser Web Push Protocol RFC 8030 self-service subscribe / list / unsubscribe")
public class PushSubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptionController.class);

    private final PushSubscriptionService service;
    private final SubscriberIdentityGuard subscriberIdentityGuard;
    private final NotifyOrgAccessGuard notifyOrgAccessGuard;

    public PushSubscriptionController(
        PushSubscriptionService service,
        SubscriberIdentityGuard subscriberIdentityGuard,
        NotifyOrgAccessGuard notifyOrgAccessGuard
    ) {
        this.service = service;
        this.subscriberIdentityGuard = subscriberIdentityGuard;
        this.notifyOrgAccessGuard = notifyOrgAccessGuard;
    }

    @PostMapping
    @Operation(
        summary = "Subscribe (upsert) browser push endpoint",
        description = "Idempotent: aynı endpointUrl için tekrar çağrı keys "
            + "güncellenir; soft-deleted ise reactivate edilir."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Endpoint created / updated / reactivated"),
        @ApiResponse(responseCode = "400", description = "Validation error (invalid URL/keys)"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Identity mismatch")
    })
    public ResponseEntity<PushSubscribeResponse> subscribe(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId,
        @Valid @RequestBody PushSubscribeRequest request
    ) {
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);

        PushSubscribeResponse response = service.subscribe(callerOrgId, subscriberId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    @Operation(
        summary = "List my active push endpoints",
        description = "Returns active endpoint metadata (UUID + UA/platform + timestamps); "
            + "raw endpointUrl / p256dh / authSecret response'da YOK (KVKK Madde 12 data minimization)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Active endpoints list"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Identity mismatch")
    })
    public ResponseEntity<PushEndpointListResponse> listMine(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);

        return ResponseEntity.ok(service.listActive(callerOrgId, subscriberId));
    }

    @DeleteMapping("/{endpointId}")
    @Operation(
        summary = "Unsubscribe (soft delete) an endpoint",
        description = "Endpoint-level soft-delete: multi-endpoint subscriber için "
            + "diğer cihazlar etkilenmez. Idempotent + privacy-safe: not-found, "
            + "already-deleted ve cross-subscriber denial hepsi 200 'no_op' "
            + "döner (endpoint enumeration koruması — Codex 019e4a57 P3 absorb)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Endpoint soft-deleted ('deleted') or no-op "
                + "('no_op' — not found, already deleted, or cross-subscriber)"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Identity mismatch")
    })
    public ResponseEntity<Map<String, Object>> unsubscribe(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId,
        @PathVariable("endpointId") UUID endpointId
    ) {
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);

        boolean deleted = service.unsubscribe(callerOrgId, subscriberId, endpointId);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("endpoint_id", endpointId.toString());
        body.put("status", deleted ? "deleted" : "no_op");
        // 404 surface only when endpoint truly missing; cross-subscriber +
        // already-deleted no_op (200) — controller-side detail leakage'i
        // önlemek için ayrım yapmaz (caller logu yeterli).
        return ResponseEntity.status(HttpStatus.OK).body(body);
    }
}
