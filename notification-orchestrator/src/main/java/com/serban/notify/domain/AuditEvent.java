package com.serban.notify.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Audit event — PII-redacted append-only (ADR-0013 D46 #7 must-have).
 *
 * <p>Faz 23.2 PR-D.1 (Codex 019dfdec absorb): partitioned table cutover.
 * Canonical table {@code audit_event_v2 PARTITION BY RANGE (occurred_at)};
 * compatibility view {@code audit_event} forwards INSERTs to v2 (V8 migration).
 *
 * <p>JPA composite PK ({@code id, occurred_at}) — partitioned table requires
 * partition key in PK. {@code @IdClass(AuditEventId.class)} pattern preferred
 * over {@code @EmbeddedId} for cleaner field-based queries.
 *
 * <p>Append-only: TRIGGER on parent {@code audit_event_v2} raises EXCEPTION
 * for UPDATE/DELETE (V8 — Codex Q3 PARTIAL absorb; was RULE DO INSTEAD NOTHING
 * silent in V1, now visible failure for testability). JPA {@code @Immutable}
 * mirrors at ORM level.
 */
@Entity
@Table(name = "audit_event_v2", schema = "notify")
@IdClass(AuditEventId.class)
@Immutable
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_event_v2_id_seq")
    @SequenceGenerator(
        name = "audit_event_v2_id_seq",
        sequenceName = "notify.audit_event_v2_id_seq",
        allocationSize = 1
    )
    private Long id;

    @Column(name = "intent_id", nullable = false, length = 64)
    private String intentId;

    @Column(name = "delivery_id")
    private Long deliveryId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    @Column(name = "topic_key", nullable = false, length = 128)
    private String topicKey;

    @Column(name = "recipient_hash", length = 64)
    private String recipientHash;

    @Column(length = 32)
    private String channel;

    @Column(name = "template_id", length = 128)
    private String templateId;

    @Column(name = "template_version")
    private Integer templateVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Id
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @PrePersist
    void prePersist() {
        if (occurredAt == null) occurredAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }
    public Long getDeliveryId() { return deliveryId; }
    public void setDeliveryId(Long deliveryId) { this.deliveryId = deliveryId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String topicKey) { this.topicKey = topicKey; }
    public String getRecipientHash() { return recipientHash; }
    public void setRecipientHash(String recipientHash) { this.recipientHash = recipientHash; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public Integer getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(Integer templateVersion) { this.templateVersion = templateVersion; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditEvent that)) return false;
        // Composite PK equality (id + occurred_at) — Codex 019dfdec Q2 absorb
        return id != null && id.equals(that.id)
            && occurredAt != null && occurredAt.equals(that.occurredAt);
    }

    @Override
    public int hashCode() { return getClass().hashCode();
    }
}
