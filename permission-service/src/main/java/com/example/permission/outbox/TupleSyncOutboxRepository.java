package com.example.permission.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TupleSyncOutboxRepository extends JpaRepository<TupleSyncOutboxEntry, Long> {

    /**
     * Fetch PENDING entries without locking (for read-only / single-instance use).
     */
    @Query("SELECT e FROM TupleSyncOutboxEntry e WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC")
    List<TupleSyncOutboxEntry> findPendingEntries();

    /**
     * Fetch up to :limit PENDING entries with SELECT FOR UPDATE SKIP LOCKED.
     * Ensures safe concurrent polling in multi-instance deployments:
     * each instance claims a distinct batch of rows.
     */
    @Query(value = "SELECT * FROM {h-schema}tuple_sync_outbox "
            + "WHERE status = 'PENDING' "
            + "ORDER BY created_at ASC "
            + "LIMIT :limit "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<TupleSyncOutboxEntry> findPendingForUpdate(@Param("limit") int limit);
}
