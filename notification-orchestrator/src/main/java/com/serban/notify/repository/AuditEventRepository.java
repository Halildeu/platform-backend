package com.serban.notify.repository;

import com.serban.notify.domain.AuditEvent;
import com.serban.notify.domain.AuditEventId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Audit event read-only repository.
 *
 * <p>**No purge method here** (Codex 019df86f post-impl bulgu #5 absorb):
 * önceki @Modifying purgeOlderThan DB rule {@code audit_event_no_delete} ile
 * çelişiyordu — silent no-op (sahte purge görüntüsü). Audit retention ayrı
 * sub-faz operasyon iş ({@link com.serban.notify.audit.AuditPartitionRetentionService}):
 * partition DETACH + grace + DROP TABLE pattern.
 *
 * <p>append-only enforcement:
 * <ul>
 *   <li>JPA: {@code @Immutable} on {@link AuditEvent} entity</li>
 *   <li>DB: {@code audit_event_v2_no_update} + {@code audit_event_v2_no_delete}
 *       TRIGGER (V8 — Codex 019dfdec Q3 absorb; raises EXCEPTION 23514)</li>
 * </ul>
 *
 * <p>PR-D.1 (Codex 019dfdec Q2 absorb): composite PK {@code AuditEventId(id, occurredAt)}
 * — partitioned table {@code audit_event_v2} requires partition key in PK.
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, AuditEventId> {

    List<AuditEvent> findByCorrelationIdOrderByOccurredAtAsc(String correlationId);

    @Query("SELECT a FROM AuditEvent a WHERE a.recipientHash = :recipientHash " +
           "ORDER BY a.occurredAt DESC")
    List<AuditEvent> findByRecipientHash(@Param("recipientHash") String recipientHash, Pageable pageable);
}
