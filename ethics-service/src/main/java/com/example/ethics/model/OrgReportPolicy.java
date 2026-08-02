package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * ES-212 — which reporting modes an organisation runs, and how long it keeps the
 * identity. See V20 for why this is data rather than configuration.
 *
 * <p>Anonymous is not stored as something that can be switched off; the column exists
 * so the row reads completely, but the schema pins it true. A tenant adds modes above
 * the anonymous floor and never removes it.
 */
@Entity
@Table(name = "ethics_org_report_policy")
public class OrgReportPolicy {

    @Id
    @Column(name = "org_id", nullable = false, updatable = false) private UUID orgId;
    @Column(name = "anonymous_enabled", nullable = false) private boolean anonymousEnabled = true;
    @Column(name = "confidential_enabled", nullable = false) private boolean confidentialEnabled;
    @Column(name = "named_enabled", nullable = false) private boolean namedEnabled;
    @Column(name = "identity_retention_days") private Integer identityRetentionDays;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "updated_by_subject", nullable = false, length = 200) private String updatedBySubject;
    @Version @Column(nullable = false) private long version;

    protected OrgReportPolicy() {}

    public OrgReportPolicy(UUID orgId, boolean confidentialEnabled, boolean namedEnabled,
                           Integer identityRetentionDays, Instant updatedAt, String updatedBySubject) {
        this.orgId = orgId;
        this.anonymousEnabled = true;
        this.confidentialEnabled = confidentialEnabled;
        this.namedEnabled = namedEnabled;
        this.identityRetentionDays = identityRetentionDays;
        this.updatedAt = updatedAt;
        this.updatedBySubject = updatedBySubject;
    }

    public UUID getOrgId() { return orgId; }
    public boolean isAnonymousEnabled() { return anonymousEnabled; }
    public boolean isConfidentialEnabled() { return confidentialEnabled; }
    public boolean isNamedEnabled() { return namedEnabled; }
    public Integer getIdentityRetentionDays() { return identityRetentionDays; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBySubject() { return updatedBySubject; }

    /**
     * Turning a mode off does not reach back and delete identities already collected
     * under it. Those rows were lawful when written and belong to open cases; their
     * life is governed by {@code identityRetentionDays} and the case's own retention,
     * not by a later change of posture. What this call changes is what new intake
     * accepts from this moment on.
     */
    public void update(boolean confidential, boolean named, Integer retentionDays,
                       Instant when, String bySubject) {
        this.confidentialEnabled = confidential;
        this.namedEnabled = named;
        this.identityRetentionDays = retentionDays;
        this.updatedAt = when;
        this.updatedBySubject = bySubject;
    }
}
