package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * ES-2 (#3271) — one immutable version of an acknowledgement template.
 *
 * <p>No setters, deliberately: the table is append-only (V19 trigger) because the audit
 * ledger records "template X version N was sent" and that reference must still resolve to
 * the same words years later. Editing a template means inserting the next version.
 */
@Entity
@Table(name = "ethics_ack_template")
public class AckTemplate {
    @Id private UUID id;
    @Column(name = "org_id") private UUID orgId;
    @Column(name = "category") private String category;
    @Column(name = "version") private int version;
    @Column(name = "body") private String body;
    @Column(name = "created_at") private Instant createdAt;
    @Column(name = "created_by") private String createdBy;

    protected AckTemplate() {}

    public AckTemplate(
            UUID id, UUID orgId, String category, int version,
            String body, Instant createdAt, String createdBy) {
        this.id = id;
        this.orgId = orgId;
        this.category = category;
        this.version = version;
        this.body = body;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getOrgId() { return orgId; }
    public String getCategory() { return category; }
    public int getVersion() { return version; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
