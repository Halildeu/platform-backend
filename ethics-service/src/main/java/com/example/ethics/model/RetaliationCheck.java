package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * ES-213 (#3375) — a scheduled question: is the person who reported still all right?
 *
 * <p>Three per closed case, at three, six and twelve months. The directive's protection
 * duty (2019/1937 art. 19, 21) does not end when a case concludes, and until this table
 * existed the system closed a case and never asked again.
 *
 * <p>Works for an anonymous reporter, which is the point. The question goes through the
 * mailbox they already hold, so being protected never requires giving a name — a scheme
 * that could only protect people who identified themselves would protect the people who
 * needed it least.
 */
@Entity
@Table(name = "ethics_retaliation_checks")
public class RetaliationCheck {

    @Id private UUID id;
    @Column(name = "case_id", nullable = false, updatable = false) private UUID caseId;
    @Column(name = "org_id", nullable = false, updatable = false) private UUID orgId;
    @Column(name = "period_months", nullable = false, updatable = false) private short periodMonths;
    @Column(name = "due_at", nullable = false, updatable = false) private Instant dueAt;
    @Column(name = "asked_at") private Instant askedAt;
    @Column private String observation;
    @Column private String risk;
    @Column private String action;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "closed_by_hash", length = 64) private String closedByHash;
    @Version @Column(nullable = false) private long version;

    protected RetaliationCheck() {}

    public RetaliationCheck(UUID id, UUID caseId, UUID orgId, short periodMonths, Instant dueAt) {
        this.id = id;
        this.caseId = caseId;
        this.orgId = orgId;
        this.periodMonths = periodMonths;
        this.dueAt = dueAt;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public UUID getOrgId() { return orgId; }
    public short getPeriodMonths() { return periodMonths; }
    public Instant getDueAt() { return dueAt; }
    public Instant getAskedAt() { return askedAt; }
    public String getObservation() { return observation; }
    public String getRisk() { return risk; }
    public String getAction() { return action; }
    public Instant getClosedAt() { return closedAt; }

    /**
     * Stamped when the question actually reaches the reporter, which is deliberately not
     * the same as {@code dueAt}. The distance between the two is the only honest measure
     * of whether this is being run at all; collapsing them into one field would make a
     * programme that never asks look identical to one that asks on time.
     */
    public void markAsked(Instant when) {
        if (askedAt == null) {
            this.askedAt = when;
        }
    }

    /**
     * Concluding a check means recording what was seen and how it was judged — and, when
     * retaliation is suspected or confirmed, what was done about it. Noticing is not
     * protecting, so the action is required rather than encouraged. The database enforces
     * the same three rules (V22), so a writer that bypasses this method cannot leave a
     * check closed and empty.
     */
    public void conclude(String observation, String risk, String action, java.util.Set<String> indicators,
                         String byHash, Instant when) {
        if (observation == null || observation.isBlank()) {
            throw new IllegalArgumentException("a concluded check must record what was observed");
        }
        if (risk == null || risk.isBlank()) {
            throw new IllegalArgumentException("a concluded check must record a risk judgement");
        }
        if (!"NONE".equals(risk) && (action == null || action.isBlank())) {
            throw new IllegalArgumentException("suspected or confirmed retaliation requires an action");
        }
        // The two halves of the answer have to agree. Naming a form of retaliation from
        // art. 19 and then judging the risk as NONE is not a subtle inconsistency — it is
        // the shape a programme takes when someone wants the checkbox without the
        // consequence, and it would leave a documented demotion sitting under a clean bill
        // of health. The database cannot see across the two tables, so the rule lives here.
        if (indicators != null && !indicators.isEmpty() && "NONE".equals(risk)) {
            throw new IllegalArgumentException(
                    "a check that names a retaliation indicator cannot be judged NONE");
        }
        this.observation = observation;
        this.risk = risk;
        this.action = action;
        this.closedByHash = byHash;
        this.closedAt = when;
    }
}
