package com.example.permission.dataaccess;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DataAccessScopeOutboxRepository
        extends JpaRepository<DataAccessScopeOutboxEntry, Long> {

    /**
     * Atomic claim of pending entries for processing. The outer UPDATE
     * uses an inner SELECT with {@code FOR UPDATE SKIP LOCKED} so multiple
     * poller instances can claim disjoint batches without blocking each
     * other. {@code RETURNING *} gives the updated rows back so the
     * caller does not need a separate SELECT round trip.
     *
     * <p>Per-tuple ordering guard (Codex 019dd0e0 iter-2 BLOCKER 2 fix —
     * Yol β typed columns): the {@code NOT EXISTS} subquery keys on
     * {@code (tuple_user, tuple_relation, tuple_object)} rather than
     * {@code scope_id} so revoke + re-grant cycles (which produce
     * different scope.id values targeting the same FGA tuple) are still
     * serialised. V22's {@code idx_scope_outbox_scope_ordering} was
     * dropped in V23 in favour of the correctness-correct
     * {@code idx_scope_outbox_tuple_ordering}.
     */
    @Modifying
    @Query(value = """
            UPDATE data_access.scope_outbox
            SET status = 'PROCESSING',
                locked_by = :pollerId,
                locked_until = :lockedUntil,
                attempt_count = attempt_count + 1
            WHERE id IN (
                SELECT id FROM data_access.scope_outbox AS outer_row
                WHERE outer_row.status = 'PENDING'
                  AND outer_row.next_attempt_at <= now()
                  AND NOT EXISTS (
                      SELECT 1 FROM data_access.scope_outbox AS older
                      WHERE older.tuple_user = outer_row.tuple_user
                        AND older.tuple_relation = outer_row.tuple_relation
                        AND older.tuple_object = outer_row.tuple_object
                        AND older.id < outer_row.id
                        AND older.status IN ('PENDING','PROCESSING')
                  )
                ORDER BY outer_row.id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            RETURNING *
            """, nativeQuery = true)
    List<DataAccessScopeOutboxEntry> claimBatch(
            @Param("pollerId") String pollerId,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("batchSize") int batchSize);

    /**
     * Calls the V22 plpgsql helper {@code data_access.recover_stuck_outbox_rows()}
     * which atomically releases PROCESSING rows whose {@code locked_until}
     * has expired (e.g. pod crash) back to PENDING. Returns the count of
     * recovered rows.
     */
    @Query(value = "SELECT data_access.recover_stuck_outbox_rows()",
            nativeQuery = true)
    int recoverStuckRows();

    /**
     * Compare-and-set finalize: marks the row PROCESSED only if it is
     * still owned by this poller (locked_by + locked_until match the
     * values the claim returned). Codex 019dd0e0 iter-2 BLOCKER 3 fix —
     * prevents a stale worker (whose lock the recovery sweep already
     * released) from overwriting a fresh worker's outcome. Returns
     * affected row count; {@code 0} signals a stale claim that the
     * caller must log without persisting.
     */
    @Modifying
    @Query(value = """
            UPDATE data_access.scope_outbox
            SET status = 'PROCESSED',
                processed_at = :processedAt,
                last_error = NULL,
                locked_by = NULL,
                locked_until = NULL
            WHERE id = :id
              AND status = 'PROCESSING'
              AND locked_by = :pollerId
              AND locked_until = :claimedLockedUntil
            """, nativeQuery = true)
    int markProcessed(@Param("id") Long id,
                      @Param("processedAt") Instant processedAt,
                      @Param("pollerId") String pollerId,
                      @Param("claimedLockedUntil") Instant claimedLockedUntil);

    /**
     * CAS retry-scheduling — same fence semantics as
     * {@link #markProcessed}. Resets status to PENDING so the next claim
     * cycle can pick the row up after {@code nextAttemptAt}.
     */
    @Modifying
    @Query(value = """
            UPDATE data_access.scope_outbox
            SET status = 'PENDING',
                next_attempt_at = :nextAttemptAt,
                last_error = :lastError,
                locked_by = NULL,
                locked_until = NULL
            WHERE id = :id
              AND status = 'PROCESSING'
              AND locked_by = :pollerId
              AND locked_until = :claimedLockedUntil
            """, nativeQuery = true)
    int markRetry(@Param("id") Long id,
                  @Param("nextAttemptAt") Instant nextAttemptAt,
                  @Param("lastError") String lastError,
                  @Param("pollerId") String pollerId,
                  @Param("claimedLockedUntil") Instant claimedLockedUntil);

    /**
     * CAS terminal-failure — same fence semantics as
     * {@link #markProcessed}. Status flips to FAILED; downstream
     * monitoring uses {@code idx_scope_outbox_failed} to surface the row
     * for operator attention.
     */
    @Modifying
    @Query(value = """
            UPDATE data_access.scope_outbox
            SET status = 'FAILED',
                last_error = :lastError,
                locked_by = NULL,
                locked_until = NULL
            WHERE id = :id
              AND status = 'PROCESSING'
              AND locked_by = :pollerId
              AND locked_until = :claimedLockedUntil
            """, nativeQuery = true)
    int markFailed(@Param("id") Long id,
                   @Param("lastError") String lastError,
                   @Param("pollerId") String pollerId,
                   @Param("claimedLockedUntil") Instant claimedLockedUntil);
}
