package com.serban.notify.repository;

import com.serban.notify.domain.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByCorrelationIdOrderByOccurredAtAsc(String correlationId);

    @Query("SELECT a FROM AuditEvent a WHERE a.recipientHash = :recipientHash " +
           "ORDER BY a.occurredAt DESC")
    List<AuditEvent> findByRecipientHash(@Param("recipientHash") String recipientHash, Pageable pageable);

    /**
     * Audit retention purge — runs as scheduled cron job (D42 90 gün default).
     * Note: Postgres rule {@code audit_event_no_delete} blocks DELETE; this
     * method exists for visibility but should NOT be invoked from runtime —
     * use external cron with {@code SUSPEND}/raw SQL grant for retention purge.
     */
    @Modifying
    @Query("DELETE FROM AuditEvent a WHERE a.occurredAt < :cutoff")
    int purgeOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
