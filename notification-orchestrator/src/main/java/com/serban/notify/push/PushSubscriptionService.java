package com.serban.notify.push;

import com.serban.notify.api.dto.PushEndpointListResponse;
import com.serban.notify.api.dto.PushSubscribeRequest;
import com.serban.notify.api.dto.PushSubscribeResponse;
import com.serban.notify.domain.SubscriberPushEndpoint;
import com.serban.notify.repository.SubscriberPushEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Subscriber push endpoint subscription service (Faz 23.7 M7 T4.2 PR-W3).
 *
 * <p>Browser self-service push subscription flow:
 * <ol>
 *   <li>Subscribe: idempotent upsert by (orgId, subscriberId, endpointUrl).
 *       Mevcut endpoint varsa keys güncellenir (re-subscribe sırasında
 *       browser yeni keys üretebilir); soft-deleted ise yeniden aktif
 *       edilir.</li>
 *   <li>List: subscriber'ın aktif endpoint'lerini döner (raw URL/keys
 *       hariç — PII minimal projection).</li>
 *   <li>Unsubscribe: endpoint-level soft delete. Subscriber kendi
 *       endpoint'ini silmek için DELETE çağırır; sadece kendisinin
 *       endpoint'i (tenant + identity guard kontrolör tarafından).</li>
 * </ol>
 *
 * <p>KVKK boundary: raw {@code endpointUrl} push service token gibi
 * davranır; response'ta YOK. Browser tarafı endpointId UUID üzerinden
 * referans verir.
 */
@Service
public class PushSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptionService.class);

    private final SubscriberPushEndpointRepository repo;

    public PushSubscriptionService(SubscriberPushEndpointRepository repo) {
        this.repo = repo;
    }

    /**
     * Subscribe (upsert) endpoint.
     *
     * <p>Idempotency key: (orgId, subscriberId, endpointUrl). Aynı browser
     * tekrar subscribe ederse aynı row update edilir (keys değişebilir,
     * deletedAt null'lanır).
     */
    @Transactional
    public PushSubscribeResponse subscribe(
        String orgId, String subscriberId, PushSubscribeRequest request
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        Optional<SubscriberPushEndpoint> existing =
            repo.findByOrgIdAndSubscriberIdAndEndpointUrl(
                orgId, subscriberId, request.endpointUrl()
            );

        if (existing.isPresent()) {
            SubscriberPushEndpoint endpoint = existing.get();
            boolean wasDeleted = endpoint.getDeletedAt() != null;
            endpoint.setP256dhKey(request.p256dhKey());
            endpoint.setAuthSecret(request.authSecret());
            if (request.userAgent() != null && !request.userAgent().isBlank()) {
                endpoint.setUserAgent(request.userAgent());
            }
            endpoint.setLastSeenAt(now);
            endpoint.setUpdatedAt(now);
            if (wasDeleted) {
                endpoint.setDeletedAt(null);
                endpoint.setFailureCount(0);
                endpoint.setLastFailureAt(null);
                endpoint.setLastFailureReason(null);
            }
            SubscriberPushEndpoint saved = repo.save(endpoint);
            String status = wasDeleted ? "reactivated" : "updated";
            log.info("push subscribe {}: endpointId={}", status, saved.getEndpointId());
            return new PushSubscribeResponse(saved.getEndpointId(), status);
        }

        SubscriberPushEndpoint endpoint = new SubscriberPushEndpoint();
        endpoint.setOrgId(orgId);
        endpoint.setSubscriberId(subscriberId);
        endpoint.setEndpointUrl(request.endpointUrl());
        endpoint.setP256dhKey(request.p256dhKey());
        endpoint.setAuthSecret(request.authSecret());
        endpoint.setUserAgent(request.userAgent());
        endpoint.setLastSeenAt(now);
        SubscriberPushEndpoint saved = repo.save(endpoint);
        log.info("push subscribe created: endpointId={}", saved.getEndpointId());
        return new PushSubscribeResponse(saved.getEndpointId(), "created");
    }

    /**
     * List active endpoints (raw URL/keys hariç — PII minimal).
     */
    @Transactional(readOnly = true)
    public PushEndpointListResponse listActive(String orgId, String subscriberId) {
        List<SubscriberPushEndpoint> endpoints =
            repo.findActiveBySubscriber(orgId, subscriberId);
        List<PushEndpointListResponse.Endpoint> projection = endpoints.stream()
            .map(e -> new PushEndpointListResponse.Endpoint(
                e.getEndpointId(),
                e.getUserAgent(),
                e.getPlatformHint(),
                e.getCreatedAt(),
                e.getLastSeenAt()
            ))
            .toList();
        return new PushEndpointListResponse(projection);
    }

    /**
     * Soft-delete an endpoint owned by the subscriber.
     *
     * <p>Authorization: caller'ın orgId + subscriberId match olduğu
     * zaten controller tarafından doğrulanmıştır
     * ({@link com.serban.notify.api.NotifyOrgAccessGuard} +
     * {@link com.serban.notify.api.SubscriberIdentityGuard}); burada
     * sadece endpoint'in caller'a ait olup olmadığı verify edilir
     * (cross-subscriber endpoint silinmesin).
     *
     * @return true if endpoint was soft-deleted; false if already
     *         deleted, not found, or owned by another subscriber
     */
    @Transactional
    public boolean unsubscribe(String orgId, String subscriberId, UUID endpointId) {
        Optional<SubscriberPushEndpoint> existing = repo.findById(endpointId);
        if (existing.isEmpty()) {
            log.warn("push unsubscribe: endpoint {} not found", endpointId);
            return false;
        }
        SubscriberPushEndpoint endpoint = existing.get();
        // Tenancy + subject match: endpoint sadece owner subscriber tarafından silinebilir
        if (!orgId.equals(endpoint.getOrgId())
            || !subscriberId.equals(endpoint.getSubscriberId())) {
            log.warn("push unsubscribe: cross-subscriber denied endpointId={}", endpointId);
            return false;
        }
        if (endpoint.getDeletedAt() != null) {
            log.info("push unsubscribe: already deleted endpointId={}", endpointId);
            return false;
        }
        int rows = repo.softDeleteByEndpointId(endpointId, OffsetDateTime.now());
        log.info("push unsubscribe: endpointId={} rows={}", endpointId, rows);
        return rows > 0;
    }
}
