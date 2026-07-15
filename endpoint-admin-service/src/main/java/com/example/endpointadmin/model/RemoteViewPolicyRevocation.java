package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Append-only early revocation of one immutable publication. */
@Entity
@Table(name = "remote_view_policy_revocations")
public class RemoteViewPolicyRevocation {
    @Id private UUID id;
    @Column(name = "publication_id", nullable = false) private UUID publicationId;
    @Column(name = "approval_id", nullable = false) private UUID approvalId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_digest", nullable = false, length = 71) private String policyDigest;
    @Column(name = "reason", nullable = false, length = 2048) private String reason;
    @Column(name = "revoked_by_subject", nullable = false, length = 255) private String revokedBySubject;
    @Column(name = "revoked_at", nullable = false) private Instant revokedAt;

    protected RemoteViewPolicyRevocation() { }

    public RemoteViewPolicyRevocation(UUID id, UUID publicationId, UUID approvalId, UUID tenantId,
                                      String policyDigest, String reason, String revokedBySubject,
                                      Instant revokedAt) {
        this.id = id;
        this.publicationId = publicationId;
        this.approvalId = approvalId;
        this.tenantId = tenantId;
        this.policyDigest = policyDigest;
        this.reason = reason;
        this.revokedBySubject = revokedBySubject;
        this.revokedAt = revokedAt;
    }

    public UUID getId() { return id; }
    public UUID getPublicationId() { return publicationId; }
    public UUID getApprovalId() { return approvalId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyDigest() { return policyDigest; }
    public String getReason() { return reason; }
    public String getRevokedBySubject() { return revokedBySubject; }
    public Instant getRevokedAt() { return revokedAt; }
}
