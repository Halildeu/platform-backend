package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * ES-213 (#3375) — what was decided about the person found at fault, and whether it was
 * actually carried out.
 *
 * <p>Deciding and applying are separate columns because they are separate acts, by
 * different people, often weeks apart. A decision with no application is a real state and
 * a visible one: it is the backlog this table exists to expose. Collapsing them would make
 * "we decided" and "we did" indistinguishable, which is the failure mode a sanctions
 * register is built to prevent.
 */
@Entity
@Table(name = "ethics_case_sanctions")
public class CaseSanction {

    /**
     * The bands of Açık Holding's İHLAL AĞIRLIK CETVELİ, with the score ranges the scale
     * itself defines. Keeping the mapping here rather than in a spreadsheet is what stops
     * two similar violations drawing different bands because two people read the table
     * differently.
     */
    public enum Band {
        HAFIF(1, 10), ORTA(11, 20), AGIR(21, 30), COK_AGIR(31, 40);

        private final int min;
        private final int max;

        Band(int min, int max) {
            this.min = min;
            this.max = max;
        }

        public static Band ofScore(int score) {
            for (Band band : values()) {
                if (score >= band.min && score <= band.max) {
                    return band;
                }
            }
            throw new IllegalArgumentException("severity score must be between 1 and 40");
        }

        boolean isAbove(Band other) {
            return ordinal() > other.ordinal();
        }
    }

    /**
     * Violations the scale marks ÇOK AĞIR regardless of what the ten criteria total to.
     * They are listed rather than inferred because each is a category where a low score is
     * itself the warning sign: a single bribe of modest value, a single act of harassment
     * with one victim and no financial impact, would otherwise band as HAFİF.
     */
    public static final Set<String> AUTOMATIC_ESCALATIONS = Set.of(
            "PUBLIC_OFFICIAL_BRIBERY",
            "SEXUAL_HARASSMENT",
            "CHILD_LABOUR",
            "FORCED_LABOUR",
            "CONCEALED_FATAL_ACCIDENT",
            "INSIDER_TRADING",
            "FORGED_IDENTITY");

    /**
     * The band an automatically escalated category must carry, whatever the score says.
     *
     * <p>Named rather than written as {@code Band.COK_AGIR} at each use so that the scale
     * and the rule cannot drift apart if the scale ever grows a fifth band.
     */
    private static final Band ESCALATION_FLOOR = Band.COK_AGIR;

    @Id private UUID id;
    @Column(name = "case_id", nullable = false, updatable = false) private UUID caseId;
    @Column(name = "org_id", nullable = false, updatable = false) private UUID orgId;
    @Column(name = "violation_category", nullable = false, length = 40, updatable = false)
    private String violationCategory;
    @Column(name = "severity_score", nullable = false) private int severityScore;
    @Column(name = "severity_band", nullable = false, length = 16) private String severityBand;
    @Column(name = "escalation_reason", length = 400) private String escalationReason;
    @Column(name = "sanction_type", nullable = false, length = 40) private String sanctionType;
    @Column(name = "decided_at", nullable = false) private Instant decidedAt;
    @Column(name = "decided_by_hash", nullable = false, length = 64) private String decidedByHash;
    @Column(name = "applied_at") private Instant appliedAt;
    @Column(name = "applied_by_hash", length = 64) private String appliedByHash;
    @Column(name = "verification_note") private String verificationNote;
    @Column(name = "appeal_state", nullable = false, length = 16) private String appealState = "NONE";
    @Version @Column(nullable = false) private long version;

    protected CaseSanction() {}

    public CaseSanction(UUID id, UUID caseId, UUID orgId, String violationCategory,
                        int severityScore, Band band,
                        String escalationReason, String sanctionType,
                        String decidedByHash, Instant decidedAt) {
        if (violationCategory == null || violationCategory.isBlank()) {
            throw new IllegalArgumentException("violation category is required");
        }
        Band fromScore = Band.ofScore(severityScore);
        if (AUTOMATIC_ESCALATIONS.contains(violationCategory) && ESCALATION_FLOOR.isAbove(band)) {
            // The whole point of the list: these do not get to be scored down. A single
            // bribe of modest value totals low on the ten criteria and would otherwise be
            // banded HAFİF, which is how a category the scale calls automatic quietly
            // becomes a warning letter.
            throw new IllegalArgumentException(
                    violationCategory + " is on the automatic-escalation list and must be banded "
                            + ESCALATION_FLOOR + ", not " + band);
        }
        if (fromScore.isAbove(band)) {
            // Reading the scale downwards is how a serious finding quietly becomes a
            // warning. The database refuses it too (V22); this is the message a human reads.
            throw new IllegalArgumentException(
                    "band " + band + " is below what score " + severityScore + " supports (" + fromScore + ")");
        }
        if (band.isAbove(fromScore) && (escalationReason == null || escalationReason.isBlank())) {
            throw new IllegalArgumentException(
                    "escalating above the score's band requires a reason");
        }
        this.id = id;
        this.caseId = caseId;
        this.orgId = orgId;
        this.violationCategory = violationCategory;
        this.severityScore = severityScore;
        this.severityBand = band.name();
        this.escalationReason = escalationReason;
        this.sanctionType = sanctionType;
        this.decidedByHash = decidedByHash;
        this.decidedAt = decidedAt;
        this.appealState = "NONE";
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public UUID getOrgId() { return orgId; }
    public String getViolationCategory() { return violationCategory; }
    public int getSeverityScore() { return severityScore; }
    public String getSeverityBand() { return severityBand; }
    public String getEscalationReason() { return escalationReason; }
    public String getSanctionType() { return sanctionType; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public String getVerificationNote() { return verificationNote; }
    public String getAppealState() { return appealState; }

    /**
     * Recording that the sanction was carried out, with a note saying how that was
     * verified. The note is required for the same reason a case needs a finding to close:
     * "applied" with nothing behind it is the state that makes a register worthless.
     */
    public void markApplied(String byHash, String verificationNote, Instant when) {
        if (verificationNote == null || verificationNote.isBlank()) {
            throw new IllegalArgumentException("applying a sanction requires a verification note");
        }
        if ("OVERTURNED".equals(appealState)) {
            throw new IllegalStateException("an overturned sanction cannot be applied");
        }
        this.appliedByHash = byHash;
        this.verificationNote = verificationNote;
        this.appliedAt = when;
    }

    /**
     * Appeals move forward only: NONE → REQUESTED → UPHELD or OVERTURNED, and no further.
     * Reopening a concluded appeal would let the same decision be re-litigated until it
     * came out the desired way, which is the outcome an appeal process exists to prevent.
     */
    public void moveAppeal(String next) {
        boolean allowed = switch (appealState) {
            case "NONE" -> "REQUESTED".equals(next);
            case "REQUESTED" -> "UPHELD".equals(next) || "OVERTURNED".equals(next);
            default -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("appeal cannot move from " + appealState + " to " + next);
        }
        this.appealState = next;
    }
}
