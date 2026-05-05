package com.serban.notify.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Idempotency key — 24h TTL window (Codex 019df86f post-impl Q1 #3 absorb).
 *
 * <p>Same (org_id, idempotency_key) within active window resolves to original
 * intent_id (HTTP 409). Beyond expires_at, new intent accepted.
 */
@Entity
@Table(name = "idempotency_key", schema = "notify")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "intent_id", nullable = false, length = 64)
    private String intentId;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyKey that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode(); // Codex post-impl bulgu fix: id-null collision avoid; entity uniqueness via id equals
    }
}
