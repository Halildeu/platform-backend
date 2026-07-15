package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One immutable, content-addressed tenant policy publication. */
@Entity
@Table(name = "remote_view_policy_publications")
public class RemoteViewPolicyPublication {

    @Id
    private UUID id;
    @Column(name = "approval_id", nullable = false)
    private UUID approvalId;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "policy_id", nullable = false, length = 128)
    private String policyId;
    @Column(name = "policy_version", nullable = false, length = 32)
    private String policyVersion;
    @Column(name = "deployment_class", nullable = false, length = 32)
    private String deploymentClass;
    @Column(name = "canonical_source", nullable = false, columnDefinition = "text")
    private String canonicalSource;
    @Column(name = "policy_digest", nullable = false, length = 71)
    private String policyDigest;
    @Column(name = "baseline_digest", nullable = false, length = 71)
    private String baselineDigest;
    @Column(name = "legal_evidence_digest", nullable = false, length = 71)
    private String legalEvidenceDigest;
    @Column(name = "legal_evidence_status", nullable = false, length = 32)
    private String legalEvidenceStatus;
    @Column(name = "supersedes_policy_digest", length = 71)
    private String supersedesPolicyDigest;
    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;
    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;
    @Column(name = "review_by", nullable = false)
    private Instant reviewBy;
    @Column(name = "legal_review_by", nullable = false)
    private Instant legalReviewBy;
    @Column(name = "published_by_subject", nullable = false, length = 255)
    private String publishedBySubject;
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    protected RemoteViewPolicyPublication() {
    }

    public RemoteViewPolicyPublication(UUID id, UUID approvalId, UUID tenantId, String policyId,
                                       String policyVersion, String deploymentClass, String canonicalSource,
                                       String policyDigest, String baselineDigest, String legalEvidenceDigest,
                                       String legalEvidenceStatus, String supersedesPolicyDigest,
                                       Instant validFrom, Instant validUntil,
                                       Instant reviewBy, Instant legalReviewBy, String publishedBySubject,
                                       Instant publishedAt) {
        this.id = id;
        this.approvalId = approvalId;
        this.tenantId = tenantId;
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.deploymentClass = deploymentClass;
        this.canonicalSource = canonicalSource;
        this.policyDigest = policyDigest;
        this.baselineDigest = baselineDigest;
        this.legalEvidenceDigest = legalEvidenceDigest;
        this.legalEvidenceStatus = legalEvidenceStatus;
        this.supersedesPolicyDigest = supersedesPolicyDigest;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.reviewBy = reviewBy;
        this.legalReviewBy = legalReviewBy;
        this.publishedBySubject = publishedBySubject;
        this.publishedAt = publishedAt;
    }

    public UUID getId() { return id; }
    public UUID getApprovalId() { return approvalId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyId() { return policyId; }
    public String getPolicyVersion() { return policyVersion; }
    public String getDeploymentClass() { return deploymentClass; }
    public String getCanonicalSource() { return canonicalSource; }
    public String getPolicyDigest() { return policyDigest; }
    public String getBaselineDigest() { return baselineDigest; }
    public String getLegalEvidenceDigest() { return legalEvidenceDigest; }
    public String getLegalEvidenceStatus() { return legalEvidenceStatus; }
    public String getSupersedesPolicyDigest() { return supersedesPolicyDigest; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public Instant getReviewBy() { return reviewBy; }
    public Instant getLegalReviewBy() { return legalReviewBy; }
    public String getPublishedBySubject() { return publishedBySubject; }
    public Instant getPublishedAt() { return publishedAt; }
}
