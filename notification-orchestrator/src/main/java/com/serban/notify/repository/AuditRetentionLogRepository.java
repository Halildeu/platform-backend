package com.serban.notify.repository;

import com.serban.notify.domain.AuditRetentionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditRetentionLogRepository extends JpaRepository<AuditRetentionLog, Long> {

    Optional<AuditRetentionLog> findByPartitionName(String partitionName);

    /**
     * Drop-eligible logs: detached + drop_after &lt;= now + dropped_at IS NULL.
     * Codex 019dfdec Q5 absorb — two-phase grace pattern.
     */
    @Query("""
        SELECT l FROM AuditRetentionLog l
         WHERE l.status = com.serban.notify.domain.AuditRetentionLog.Status.detached
           AND l.dropAfter <= :now
           AND l.droppedAt IS NULL
           AND l.dryRun = false
        """)
    List<AuditRetentionLog> findDropEligible(@Param("now") OffsetDateTime now);
}
