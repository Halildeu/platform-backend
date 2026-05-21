package com.serban.notify.repository;

import com.serban.notify.domain.ErasureRequestLedger;
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
 * Repository for {@link ErasureRequestLedger} (Faz 23.2 M3 R2 PR-K1 —
 * Codex {@code 019e4950} P0 #1 absorb).
 *
 * <p>KVKK Madde 13.2 30-gün SLA takip ledger'ı; append-only saklanır,
 * 90-gün retention purge buna dokunmaz.
 */
@Repository
public interface ErasureRequestLedgerRepository
    extends JpaRepository<ErasureRequestLedger, UUID> {

    /**
     * Idempotency lookup — aynı (orgId, idempotencyKey) ikinci başvuru
     * mevcut row döner; ledger insert tekrarı yok.
     */
    Optional<ErasureRequestLedger> findByOrgIdAndIdempotencyKey(
        String orgId,
        String idempotencyKey
    );

    /**
     * DPO subject lookup — KVKK Madde 13 right-to-information için
     * verilen başvuru tarihçesi. Newest-first.
     */
    List<ErasureRequestLedger> findByOrgIdAndSubjectRefHmacOrderByReceivedAtDesc(
        String orgId,
        String subjectRefHmac
    );

    /**
     * ErasureSlaWatchdog scheduled scan: {@code due_at <= now} ve status
     * terminal değil (RECEIVED / PROCESSING / LEGAL_HOLD) → SLA breach.
     *
     * <p>Index: {@code idx_erasure_ledger_sla_scan} (partial — terminal
     * status hariç).
     */
    @Query("""
        SELECT l FROM ErasureRequestLedger l
        WHERE l.dueAt <= :now
          AND l.status IN (
              com.serban.notify.domain.ErasureRequestLedger.Status.RECEIVED,
              com.serban.notify.domain.ErasureRequestLedger.Status.PROCESSING,
              com.serban.notify.domain.ErasureRequestLedger.Status.LEGAL_HOLD
          )
        ORDER BY l.dueAt ASC
        """)
    List<ErasureRequestLedger> findOverdueRequests(@Param("now") OffsetDateTime now);

    /**
     * Status transition with timestamp atomicity. PROCESSING/RECEIVED →
     * COMPLETED geçişinde {@code closed_at}, {@code last_audit_event_id}
     * ve {@code last_audit_event_occurred_at} birlikte yazılır.
     * LEGAL_HOLD ve terminal status hariç. Optimistic — caller
     * @Transactional sağlar.
     *
     * <p>Codex 019e499c REVISE P1 #4 absorb: audit_event_v2 schema
     * uyumlu BIGINT + occurred_at composite (UUID değil).
     */
    @Modifying
    @Query("""
        UPDATE ErasureRequestLedger l
        SET l.status = com.serban.notify.domain.ErasureRequestLedger.Status.COMPLETED,
            l.closedAt = :closedAt,
            l.lastAuditEventId = :auditEventId,
            l.lastAuditEventOccurredAt = :auditEventOccurredAt,
            l.updatedAt = :closedAt
        WHERE l.requestId = :requestId
          AND l.status IN (
              com.serban.notify.domain.ErasureRequestLedger.Status.RECEIVED,
              com.serban.notify.domain.ErasureRequestLedger.Status.PROCESSING
          )
        """)
    int markCompleted(
        @Param("requestId") UUID requestId,
        @Param("closedAt") OffsetDateTime closedAt,
        @Param("auditEventId") Long auditEventId,
        @Param("auditEventOccurredAt") OffsetDateTime auditEventOccurredAt
    );

    /**
     * Status transition FAILED — KVKK Madde 13.2 audit kanıtı için
     * runtime hatasında ledger row görünür kalmalı. Codex 019e499c
     * REVISE P0 #1 absorb: durable failure tracking.
     */
    @Modifying
    @Query("""
        UPDATE ErasureRequestLedger l
        SET l.status = com.serban.notify.domain.ErasureRequestLedger.Status.FAILED,
            l.closedAt = :closedAt,
            l.failureReason = :failureReason,
            l.updatedAt = :closedAt
        WHERE l.requestId = :requestId
          AND l.status IN (
              com.serban.notify.domain.ErasureRequestLedger.Status.RECEIVED,
              com.serban.notify.domain.ErasureRequestLedger.Status.PROCESSING
          )
        """)
    int markFailed(
        @Param("requestId") UUID requestId,
        @Param("closedAt") OffsetDateTime closedAt,
        @Param("failureReason") String failureReason
    );

    /**
     * Reporting: org bazında belirli tarih aralığındaki başvurular
     * (aylık KVKK denetim raporu).
     */
    @Query("""
        SELECT l FROM ErasureRequestLedger l
        WHERE l.orgId = :orgId
          AND l.receivedAt >= :since
          AND l.receivedAt < :until
        ORDER BY l.receivedAt DESC
        """)
    List<ErasureRequestLedger> findByOrgIdAndReceivedAtRange(
        @Param("orgId") String orgId,
        @Param("since") OffsetDateTime since,
        @Param("until") OffsetDateTime until
    );
}
