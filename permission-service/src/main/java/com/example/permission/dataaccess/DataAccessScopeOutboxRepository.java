package com.example.permission.dataaccess;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Faz 21.3 PR-G follow-up — Codex 019dd0e0 iter-1 BLOCKER 1 (MAJOR
 * tech-debt) resolution: {@link #claimBatch} moved to
 * {@link DataAccessScopeOutboxRepositoryImpl} via the Spring Data JPA
 * "custom fragment" pattern. The CAS finalize methods below
 * ({@link #markProcessed}, {@link #markRetry}, {@link #markFailed}) keep
 * their {@code @Modifying int} shape because that IS idiomatic for
 * affected-row-count UPDATEs.
 */
public interface DataAccessScopeOutboxRepository
        extends JpaRepository<DataAccessScopeOutboxEntry, Long>,
                DataAccessScopeOutboxRepositoryCustom {

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
