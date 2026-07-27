package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Who is on a case, named as a principal rather than as a label (ES-203, B+ slice 1).
 *
 * <p>The subject here is the same Keycloak subject the OpenFGA tuple names, so the database and
 * the policy engine cannot disagree about <em>who</em> while agreeing about <em>what</em>. The
 * field it replaces — {@code ethics_cases.assigned_to} — is free text that has held values like
 * {@code jbjb}; a label cannot be handed to an authorization check.
 *
 * <p>No name or email is stored. A display name is derived from the identity source at read time;
 * keeping one here as an editable field would recreate the second-source problem in a new column.
 */
@Entity
@Table(name = "ethics_case_participants")
public class CaseParticipant {

    /**
     * The roles the authorization model defines. A free-text role would reintroduce exactly the
     * problem this entity exists to remove, so the set is closed here and again by a CHECK
     * constraint in the schema — the database is the boundary that survives a future caller which
     * forgets to ask.
     */
    public static final Set<String> ALLOWED_ROLES = Set.of("triager", "handler", "evidence_approver");

    @Id private UUID id;
    @Column(name = "case_id", nullable = false, updatable = false) private UUID caseId;
    @Column(name = "org_id", nullable = false, updatable = false) private UUID orgId;
    @Column(name = "kc_subject", nullable = false, updatable = false) private String kcSubject;
    @Column(nullable = false, updatable = false) private String role;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "created_by_hash", nullable = false, updatable = false) private String createdByHash;

    protected CaseParticipant() {}

    public CaseParticipant(UUID id, UUID caseId, UUID orgId, String kcSubject, String role,
                           Instant createdAt, String createdByHash) {
        if (!ALLOWED_ROLES.contains(role)) {
            throw new IllegalArgumentException("unsupported participant role: " + role);
        }
        this.id = id;
        this.caseId = caseId;
        this.orgId = orgId;
        this.kcSubject = kcSubject;
        this.role = role;
        this.createdAt = createdAt;
        this.createdByHash = createdByHash;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public UUID getOrgId() { return orgId; }
    public String getKcSubject() { return kcSubject; }
    public String getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedByHash() { return createdByHash; }
}
