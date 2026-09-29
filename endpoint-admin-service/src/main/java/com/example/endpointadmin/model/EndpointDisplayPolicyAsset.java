package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * #508 Endpoint Display Policy — a managed wallpaper image (platform-backend#1203,
 * backs the V83 {@code endpoint_display_policy_assets} table).
 *
 * <p>Content-addressed and immutable: the {@code sha256} is computed server-side
 * at upload and a policy references the image only by that hash, so a hash names
 * exactly one byte sequence for the lifetime of the row. The V83 trigger raises
 * on UPDATE/DELETE; there is deliberately no setter for the content.
 *
 * <p>{@code content} is a plain {@code byte[]} with no {@code @Lob}: under
 * Hibernate 6 on PostgreSQL {@code @Lob byte[]} maps to a large-object
 * {@code oid}, not {@code bytea}, and would silently diverge from the migration.
 * Same mapping as {@link EndpointTpmDeviceBinding#getAkName()}.
 */
@Entity
@Table(name = "endpoint_display_policy_assets",
        indexes = {
                @Index(name = "ux_edpa_org_sha256", columnList = "org_id,sha256", unique = true)
        })
public class EndpointDisplayPolicyAsset {

    /** Prefix of a policy {@code assetRef} that points at a managed asset. */
    public static final String MANAGED_REF_PREFIX = "asset:sha256:";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;

    @Column(name = "content_type", nullable = false, updatable = false, length = 64)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(name = "content", nullable = false, updatable = false)
    private byte[] content;

    @Column(name = "created_by_subject", nullable = false, updatable = false, length = 255)
    private String createdBySubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EndpointDisplayPolicyAsset() {
        // JPA
    }

    public EndpointDisplayPolicyAsset(UUID tenantId, String sha256, String contentType,
                                      byte[] content, String createdBySubject, Instant createdAt) {
        this.tenantId = tenantId;
        this.sha256 = sha256;
        this.contentType = contentType;
        this.content = content;
        this.sizeBytes = content.length;
        this.createdBySubject = createdBySubject;
        this.createdAt = createdAt;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (orgId == null && tenantId != null) {
            orgId = tenantId;
        }
    }

    /** The {@code assetRef} a policy uses to point at this asset. */
    public String managedRef() {
        return MANAGED_REF_PREFIX + sha256;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getOrgId() { return orgId; }
    public String getSha256() { return sha256; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public byte[] getContent() { return content; }
    public String getCreatedBySubject() { return createdBySubject; }
    public Instant getCreatedAt() { return createdAt; }
}
