package com.example.auditconsumer.repository;

import com.example.auditconsumer.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — audit_event repository.
 *
 * <p><b>Insert + select only.</b> No update/delete query method is declared, and
 * the entity is {@code @Immutable}; the DB-level append-only trigger is the hard
 * backstop. The audit write path is the {@link #insertOnConflictDoNothing} native
 * upsert (NOT {@code save()}): an atomic {@code INSERT ... ON CONFLICT (dedup_key)
 * DO NOTHING} that returns the affected-row count so a redelivery race resolves
 * to a clean DUPLICATE (0 rows) WITHOUT aborting the transaction — the bug a
 * {@code save()}→catch→{@code SELECT} classifier would hit (a unique-violation
 * leaves the PG transaction in an aborted state, so the follow-up SELECT raises
 * "current transaction is aborted").
 *
 * <p>{@code tenantId} is the numeric companyId (producer contract), not a UUID.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Tenant chain tail: the most recent row by seq (chain-tail lookup). */
    Optional<AuditEvent> findTop1ByTenantIdOrderBySeqDesc(Long tenantId);

    /** Verifier feed: every row for a tenant in chain order (oldest → newest). */
    List<AuditEvent> findByTenantIdOrderBySeqAsc(Long tenantId);

    /** Idempotency probe (defence-in-depth; the unique constraint is authoritative). */
    boolean existsByDedupKey(String dedupKey);

    /**
     * Atomic idempotent audit insert. Returns the affected-row count:
     * <ul>
     *   <li>{@code 1} → the row was INSERTed (new event);</li>
     *   <li>{@code 0} → a row with this {@code dedup_key} already exists, so the
     *       {@code ON CONFLICT (dedup_key) DO NOTHING} skipped it — an ack-able
     *       DUPLICATE, and crucially the transaction is NOT aborted (no
     *       unique-violation was raised).</li>
     * </ul>
     *
     * <p>Only a {@code dedup_key} conflict is absorbed. Any OTHER constraint
     * (the {@code UNIQUE(id)}, a {@code NOT NULL}, or the {@code require_hash}
     * BEFORE-INSERT trigger) is NOT covered by this {@code ON CONFLICT} target,
     * so it still raises a {@code DataIntegrityViolationException} that propagates
     * out — the consumer then leaves the entry in the PEL for redelivery/DLQ
     * (no silent audit loss). {@code seq} is the DB {@code BIGSERIAL}; {@code
     * ingested_at} uses the column {@code DEFAULT now()} (NOT bound here) so the
     * persist-time semantics are unchanged.
     */
    @Modifying
    @Query(value = """
            INSERT INTO audit_event (
                id, tenant_id, event_type, session_id, user_id, chunk_seq,
                http_status, rejection_code, retry_after_seconds, correlation_id,
                event_timestamp, dedup_key, stream_entry_id,
                prev_hash, entry_hash, entry_hash_alg, entry_hash_version
            ) VALUES (
                :id, :tenantId, :eventType, :sessionId, :userId, :chunkSeq,
                :httpStatus, :rejectionCode, :retryAfterSeconds, :correlationId,
                :eventTimestamp, :dedupKey, :streamEntryId,
                :prevHash, :entryHash, :entryHashAlg, :entryHashVersion
            )
            ON CONFLICT (dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertOnConflictDoNothing(
            @Param("id") UUID id,
            @Param("tenantId") Long tenantId,
            @Param("eventType") String eventType,
            @Param("sessionId") String sessionId,
            @Param("userId") Long userId,
            @Param("chunkSeq") Long chunkSeq,
            @Param("httpStatus") Integer httpStatus,
            @Param("rejectionCode") String rejectionCode,
            @Param("retryAfterSeconds") Long retryAfterSeconds,
            @Param("correlationId") String correlationId,
            @Param("eventTimestamp") Instant eventTimestamp,
            @Param("dedupKey") String dedupKey,
            @Param("streamEntryId") String streamEntryId,
            @Param("prevHash") String prevHash,
            @Param("entryHash") String entryHash,
            @Param("entryHashAlg") String entryHashAlg,
            @Param("entryHashVersion") Integer entryHashVersion);
}
