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
     * <p>Per-scope ordering guard (Codex 019dcf5c risk #1):
     * the {@code NOT EXISTS} subquery ensures only the oldest PENDING
     * (or already-PROCESSING) row per {@code scope_id} is claimable —
     * preventing GRANT/REVOKE same-scope races under future
     * multi-worker scale-up.
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
                      WHERE older.scope_id = outer_row.scope_id
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
}
