package com.example.ethics.repository;

import com.example.ethics.model.AuditOutbox;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditOutboxRepository extends JpaRepository<AuditOutbox, UUID> {

    List<AuditOutbox> findByClaimTokenOrderByCreatedAtAsc(UUID claimToken);

    /**
     * Everything that has happened to one case, oldest first.
     *
     * <p>Tenant-scoped on purpose: the audit trail is the record of who touched a
     * whistleblowing report, and a lookup that omitted {@code org_id} could answer
     * across orgs.
     */
    List<AuditOutbox> findAllByOrgIdAndAggregateIdOrderByCreatedAtAsc(UUID orgId, UUID aggregateId);

    /**
     * Every recorded event that belongs to a case — including the ones filed under one of
     * its attachments.
     *
     * <p>{@code aggregate_id} is polymorphic with no discriminator: a case event carries the
     * case id, an evidence event carries the <em>attachment</em> id. Querying by case id
     * alone therefore returns the case's own events and silently drops its entire evidence
     * custody chain. On the live cell one case showed a single event by that query while
     * thirteen evidence events about the same case sat one join away.
     *
     * <p>Written as an explicit union of the two id sources rather than a join on event-type
     * prefix. Reading the type out of a name would tie the history to a naming convention
     * that nothing enforces; the attachment table already knows which case it belongs to.
     */
    @org.springframework.data.jpa.repository.Query("""
        select a from AuditOutbox a
        where a.orgId = :orgId
          and (a.aggregateId = :caseId
               or a.aggregateId in (select e.id from EvidenceAttachment e where e.caseId = :caseId))
        order by a.createdAt asc
        """)
    List<AuditOutbox> findCaseHistory(@org.springframework.data.repository.query.Param("orgId") UUID orgId,
                                      @org.springframework.data.repository.query.Param("caseId") UUID caseId);

    long countByStatusIn(Collection<String> statuses);

    long countByStatus(String status);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_audit_outbox
            SET status = 'PENDING',
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = :now,
                last_error_code = 'LEASE_EXPIRED'
            WHERE status = 'PROCESSING'
              AND locked_until < :now
            """, nativeQuery = true)
    int recoverExpiredLeases(@Param("now") Instant now);

    /**
     * Atomically leases a bounded batch. PostgreSQL SKIP LOCKED permits safe
     * horizontal workers without two workers delivering the same row.
     */
    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_audit_outbox
            SET status = 'PROCESSING',
                claim_token = :claimToken,
                locked_until = :lockedUntil,
                attempt_count = attempt_count + 1,
                last_error_code = NULL
            WHERE id IN (
                SELECT id
                FROM {h-schema}ethics_audit_outbox
                WHERE status = 'PENDING'
                  AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int claimDue(
            @Param("claimToken") UUID claimToken,
            @Param("now") Instant now,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_audit_outbox
            SET status = 'DELIVERED',
                delivered_at = :deliveredAt,
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = NULL,
                last_error_code = NULL
            WHERE id = :id
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
              AND locked_until = :lockedUntil
            """, nativeQuery = true)
    int markDelivered(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("deliveredAt") Instant deliveredAt);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_audit_outbox
            SET status = 'PENDING',
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = :nextAttemptAt,
                last_error_code = :errorCode
            WHERE id = :id
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
              AND locked_until = :lockedUntil
            """, nativeQuery = true)
    int markRetry(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorCode") String errorCode);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_audit_outbox
            SET status = 'DEAD_LETTER',
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = NULL,
                last_error_code = :errorCode
            WHERE id = :id
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
              AND locked_until = :lockedUntil
            """, nativeQuery = true)
    int markDeadLetter(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("errorCode") String errorCode);
}
