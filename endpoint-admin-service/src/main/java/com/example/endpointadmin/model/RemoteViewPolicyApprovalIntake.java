package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Strict-input provenance for a publishable remote-view policy approval. */
@Entity
@Table(name = "remote_view_policy_approval_intakes")
public class RemoteViewPolicyApprovalIntake {

    @Id
    @Column(name = "approval_id", nullable = false)
    private UUID approvalId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_id", nullable = false, length = 128)
    private String policyId;

    @Column(name = "policy_version", nullable = false, length = 32)
    private String policyVersion;

    @Column(name = "canonical_source", nullable = false, columnDefinition = "text")
    private String canonicalSource;

    @Column(name = "policy_digest", nullable = false, length = 71)
    private String policyDigest;

    @Column(name = "created_by_subject", nullable = false, length = 255)
    private String createdBySubject;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RemoteViewPolicyApprovalIntake() {
    }

    public RemoteViewPolicyApprovalIntake(UUID approvalId, UUID tenantId, String policyId, String policyVersion,
                                          String canonicalSource, String policyDigest,
                                          String createdBySubject, Instant createdAt) {
        this.approvalId = approvalId;
        this.tenantId = tenantId;
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.canonicalSource = canonicalSource;
        this.policyDigest = policyDigest;
        this.createdBySubject = createdBySubject;
        this.createdAt = createdAt;
    }

    public UUID getApprovalId() { return approvalId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyId() { return policyId; }
    public String getPolicyVersion() { return policyVersion; }
    public String getCanonicalSource() { return canonicalSource; }
    public String getPolicyDigest() { return policyDigest; }
    public String getCreatedBySubject() { return createdBySubject; }
    public Instant getCreatedAt() { return createdAt; }
}
