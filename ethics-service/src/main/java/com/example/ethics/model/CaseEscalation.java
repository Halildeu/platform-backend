package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * ES-301 — one escalation level reached on one obligation of one case (#882).
 *
 * <p>A breach is computed; an escalation is recorded. The difference is what survives: a
 * list that says "overdue" today says something else once the case is acknowledged, whereas
 * this row still says that on {@code escalatedAt} the acknowledgement was
 * {@code escalatedAt - dueAt} late and the matter stood at level {@code level}. That is the
 * record an organisation is later asked for — not whether it was behind, but when it knew.
 *
 * <p>Self-describing on purpose. {@code thresholdAt} is the instant this level's step fell
 * due under the policy in force when the row was written, so a later change to
 * {@code ethics.sla.escalation.steps} cannot rewrite what level 2 meant last quarter: the
 * row carries its own threshold rather than pointing at a setting that may have moved.
 *
 * <p>Every column is {@code updatable = false} and the PostgreSQL half (V26) refuses UPDATE
 * and DELETE outright: an escalation cannot be softened or removed once it has happened.
 * Meeting the obligation afterwards stops further levels; it does not erase the ones reached.
 *
 * <p>Carries no actor. Nobody escalates a case by hand here — the clock does, from the
 * case's own timestamps — so a person's hash in this row would be a fiction about who acted.
 */
@Entity
@Table(name = "ethics_case_escalation")
public class CaseEscalation {

    /** EU 2019/1937 art. 9(1)(b): acknowledge receipt within seven days. */
    public static final String ACKNOWLEDGEMENT = "ACKNOWLEDGEMENT";

    /**
     * The reporter-feedback window as this service measures it: {@code createdAt} plus
     * {@code ethics.sla.feedback-within} (see {@code CaseSlaClock#feedback}). Art. 9(1)(f)
     * counts three months from the acknowledgement; the service's window is the owner's
     * stricter product threshold and this slice records against it, unchanged.
     */
    public static final String FEEDBACK = "FEEDBACK";

    @Id private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false) private UUID caseId;
    @Column(name = "org_id", nullable = false, updatable = false) private UUID orgId;
    @Column(nullable = false, updatable = false, length = 20) private String obligation;
    @Column(nullable = false, updatable = false) private int level;
    @Column(name = "due_at", nullable = false, updatable = false) private Instant dueAt;
    @Column(name = "threshold_at", nullable = false, updatable = false) private Instant thresholdAt;
    @Column(name = "escalated_at", nullable = false, updatable = false) private Instant escalatedAt;

    protected CaseEscalation() {}

    public CaseEscalation(UUID id, UUID caseId, UUID orgId, String obligation, int level,
            Instant dueAt, Instant thresholdAt, Instant escalatedAt) {
        if (!ACKNOWLEDGEMENT.equals(obligation) && !FEEDBACK.equals(obligation)) {
            throw new IllegalArgumentException("unknown obligation");
        }
        if (level < 1) throw new IllegalArgumentException("escalation levels start at 1");
        if (thresholdAt.isBefore(dueAt)) {
            throw new IllegalArgumentException("an escalation threshold sits at or after the deadline, never before");
        }
        if (!escalatedAt.isAfter(thresholdAt)) {
            throw new IllegalArgumentException("an escalation is recorded after its threshold has passed, never before");
        }
        this.id = id;
        this.caseId = caseId;
        this.orgId = orgId;
        this.obligation = obligation;
        this.level = level;
        this.dueAt = dueAt;
        this.thresholdAt = thresholdAt;
        this.escalatedAt = escalatedAt;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public UUID getOrgId() { return orgId; }
    public String getObligation() { return obligation; }
    public int getLevel() { return level; }
    public Instant getDueAt() { return dueAt; }
    public Instant getThresholdAt() { return thresholdAt; }
    public Instant getEscalatedAt() { return escalatedAt; }
}
