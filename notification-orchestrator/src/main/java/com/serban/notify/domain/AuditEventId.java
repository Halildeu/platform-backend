package com.serban.notify.domain;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Composite PK for {@link AuditEvent} (Faz 23.2 PR-D.1 — Codex 019dfdec Q2 absorb).
 *
 * <p>Partitioned table {@code audit_event_v2} requires partition key (occurred_at)
 * in primary key. Hibernate {@code @IdClass} pattern preferred over {@code @EmbeddedId}
 * to keep entity field-based queries clean (existing AuditEventRepository methods
 * unchanged signature-wise; only repo type signature changes from
 * {@code JpaRepository<AuditEvent, Long>} to {@code JpaRepository<AuditEvent, AuditEventId>}).
 */
public class AuditEventId implements Serializable {

    private Long id;
    private OffsetDateTime occurredAt;

    public AuditEventId() {}

    public AuditEventId(Long id, OffsetDateTime occurredAt) {
        this.id = id;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditEventId that)) return false;
        return Objects.equals(id, that.id) && Objects.equals(occurredAt, that.occurredAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, occurredAt);
    }
}
