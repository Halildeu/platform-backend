package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** ES-403 — a product an organisation holds. See V15 for why it carries no billing facts. */
@Entity
@Table(name = "ethics_org_subscription")
public class OrgSubscription {

    @Id private UUID id;
    @Column(name = "org_id", nullable = false, updatable = false) private UUID orgId;
    @Column(name = "product_id", nullable = false, updatable = false, length = 80) private String productId;
    @Column(nullable = false) private boolean active;
    @Column(name = "granted_at", nullable = false, updatable = false) private Instant grantedAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    protected OrgSubscription() {}

    public OrgSubscription(UUID id, UUID orgId, String productId, Instant grantedAt) {
        this.id = id;
        this.orgId = orgId;
        this.productId = productId;
        this.active = true;
        this.grantedAt = grantedAt;
    }

    public UUID getOrgId() { return orgId; }
    public String getProductId() { return productId; }
    public boolean isActive() { return active; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    /** Revoking keeps the row: what a customer once held is part of the record. */
    public void revoke(Instant when) {
        if (!active) return;
        this.active = false;
        this.revokedAt = when;
    }
}
