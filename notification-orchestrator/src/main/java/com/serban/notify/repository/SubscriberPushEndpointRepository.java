package com.serban.notify.repository;

import com.serban.notify.domain.SubscriberPushEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SubscriberPushEndpoint} (Faz 23.7 M7 T4.2 PR-W1).
 */
@Repository
public interface SubscriberPushEndpointRepository
    extends JpaRepository<SubscriberPushEndpoint, UUID> {

    /**
     * Subscriber'ın aktif endpoint'lerini bul (dispatch için).
     * Soft-deleted (deleted_at IS NOT NULL) hariç.
     */
    @Query("""
        SELECT e FROM SubscriberPushEndpoint e
        WHERE e.orgId = :orgId
          AND e.subscriberId = :subscriberId
          AND e.deletedAt IS NULL
        ORDER BY e.lastSeenAt DESC
        """)
    List<SubscriberPushEndpoint> findActiveBySubscriber(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId
    );

    /**
     * Idempotent upsert lookup — aynı endpoint_url ikinci subscribe
     * mevcut row döner; subscriber re-register (keys değişebilir).
     */
    Optional<SubscriberPushEndpoint> findByOrgIdAndSubscriberIdAndEndpointUrl(
        String orgId, String subscriberId, String endpointUrl
    );

    /**
     * Soft delete — KVKK Madde 17 right-to-erasure path. ErasureService
     * tarafından subscriber'ın tüm endpoint'lerini deactivate.
     */
    @Modifying
    @Query("""
        UPDATE SubscriberPushEndpoint e
        SET e.deletedAt = :now,
            e.updatedAt = :now
        WHERE e.orgId = :orgId
          AND e.subscriberId = :subscriberId
          AND e.deletedAt IS NULL
        """)
    int softDeleteBySubscriber(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId,
        @Param("now") OffsetDateTime now
    );

    /**
     * Failure counter increment — RFC 8030 410/404 response sonrası.
     * Threshold (örn. 3) aşılırsa caller soft delete tetikler.
     */
    @Modifying
    @Query("""
        UPDATE SubscriberPushEndpoint e
        SET e.failureCount = e.failureCount + 1,
            e.lastFailureAt = :now,
            e.lastFailureReason = :reason,
            e.updatedAt = :now
        WHERE e.endpointId = :endpointId
        """)
    int incrementFailure(
        @Param("endpointId") UUID endpointId,
        @Param("now") OffsetDateTime now,
        @Param("reason") String reason
    );

    /**
     * Atomic upsert (Faz 23.7 M7 T4.2 PR-W3 iter-3 — Codex
     * {@code 019e4a57} P1 absorb).
     *
     * <p>Race-safe idempotent upsert via PostgreSQL native
     * {@code INSERT ... ON CONFLICT (org_id, subscriber_id, endpoint_url)
     * DO UPDATE ...}. Constraint çakışması tek SQL statement içinde
     * çözülür; aynı transaction'da unique violation exception yakalamaya
     * gerek yok (PostgreSQL transaction abort state'e düşmez).
     *
     * <p>UPSERT davranışı:
     * <ul>
     *   <li>INSERT (yeni satır): {@code endpoint_id = gen_random_uuid()};
     *       failure counter 0; createdAt = updatedAt = lastSeenAt = NOW()</li>
     *   <li>UPDATE (mevcut satır): keys + lastSeenAt + updatedAt güncel;
     *       userAgent boş ise mevcut korunur (COALESCE); reactivation
     *       durumunda deleted_at NULL + failure counter reset</li>
     * </ul>
     *
     * <p>Return: row count (always 1 — INSERT veya UPDATE biri başarılı).
     * {@code endpoint_id} caller tarafından upsert sonrası
     * {@link #findByOrgIdAndSubscriberIdAndEndpointUrl} ile fetch edilir.
     * Status semantics (created/updated/reactivated) caller'ın upsert
     * öncesi pre-check sonucundan türetilir.
     *
     * <p>NOT: schema name {@code notify.} hard-coded; mevcut tüm
     * native query'ler aynı pattern'i kullanıyor.
     */
    @Modifying
    @Query(value = """
        INSERT INTO notify.subscriber_push_endpoint
            (endpoint_id, org_id, subscriber_id, endpoint_url,
             p256dh_key, auth_secret, user_agent, platform_hint,
             expiration_time, last_seen_at, failure_count,
             last_failure_at, last_failure_reason,
             deleted_at, created_at, updated_at)
        VALUES
            (gen_random_uuid(), :orgId, :subscriberId, :endpointUrl,
             :p256dhKey, :authSecret, :userAgent, NULL,
             NULL, :now, 0,
             NULL, NULL,
             NULL, :now, :now)
        ON CONFLICT (org_id, subscriber_id, endpoint_url) DO UPDATE
        SET p256dh_key = EXCLUDED.p256dh_key,
            auth_secret = EXCLUDED.auth_secret,
            user_agent = COALESCE(EXCLUDED.user_agent,
                                  notify.subscriber_push_endpoint.user_agent),
            last_seen_at = EXCLUDED.last_seen_at,
            updated_at = EXCLUDED.updated_at,
            deleted_at = NULL,
            failure_count = 0,
            last_failure_at = NULL,
            last_failure_reason = NULL
        """, nativeQuery = true)
    int upsertAtomic(
        @Param("orgId") String orgId,
        @Param("subscriberId") String subscriberId,
        @Param("endpointUrl") String endpointUrl,
        @Param("p256dhKey") String p256dhKey,
        @Param("authSecret") String authSecret,
        @Param("userAgent") String userAgent,
        @Param("now") OffsetDateTime now
    );

    /**
     * Endpoint-level soft delete (Faz 23.7 M7 T4.2 PR-W3).
     *
     * <p>Subscriber'ın TEK endpoint'ini soft-delete eder (multi-endpoint
     * subscriber için cihaz boundary korunur). User self-service DELETE
     * /api/v1/notify/push/subscribe/{endpointId} bu metodu çağırır.
     *
     * <p>{@link #softDeleteBySubscriber} subscriber'ın TÜM endpoint'lerini
     * siler (KVKK §11 erasure); bu metod TEK endpoint için.
     *
     * @return 0 if endpoint already deleted or not found; 1 if soft-deleted
     */
    @Modifying
    @Query("""
        UPDATE SubscriberPushEndpoint e
        SET e.deletedAt = :now,
            e.updatedAt = :now
        WHERE e.endpointId = :endpointId
          AND e.deletedAt IS NULL
        """)
    int softDeleteByEndpointId(
        @Param("endpointId") UUID endpointId,
        @Param("now") OffsetDateTime now
    );
}
