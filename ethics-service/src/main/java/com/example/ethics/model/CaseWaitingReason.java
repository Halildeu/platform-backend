package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * ES-301 — why a case is waiting, recorded without moving any deadline.
 *
 * <p>EU 2019/1937 starts the seven days at receipt and the three months at acknowledgement
 * and provides no suspension. A wait that reduced its own duration would not be measuring
 * the obligation; it would be a way to make a breach disappear administratively. So this
 * annotates, and {@code CaseSlaClock} never sees it — the class takes only the case's own
 * timestamps, so there is no parameter through which a wait could reach the arithmetic.
 *
 * <p>The reason is a closed vocabulary rather than free text. Free text here would collect
 * names: "waiting for Ahmet to answer" puts a person into a column nothing sanitises, in a
 * product whose whole point is that people's names are handled carefully.
 *
 * <p>A resume writes {@code endedAt} on the open row rather than deleting it, so what a case
 * waited for survives being answered.
 */
@Entity
@Table(name = "ethics_case_waiting_reason")
public class CaseWaitingReason {

    /** Waiting on the person who filed the report. */
    public static final String AWAITING_REPORTER = "AWAITING_REPORTER";

    /** Waiting on a regulator, prosecutor or comparable body outside the organisation. */
    public static final String AWAITING_EXTERNAL_AUTHORITY = "AWAITING_EXTERNAL_AUTHORITY";

    /** Waiting on someone inside the organisation who is not the reporter. */
    public static final String AWAITING_INTERNAL_INPUT = "AWAITING_INTERNAL_INPUT";

    @Id private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false) private UUID caseId;
    @Column(name = "org_id", nullable = false, updatable = false) private UUID orgId;
    @Column(nullable = false, updatable = false, length = 40) private String reason;
    @Column(name = "started_at", nullable = false, updatable = false) private Instant startedAt;
    @Column(name = "ended_at") private Instant endedAt;

    protected CaseWaitingReason() {}

    public CaseWaitingReason(UUID id, UUID caseId, UUID orgId, String reason, Instant startedAt) {
        this.id = id;
        this.caseId = caseId;
        this.orgId = orgId;
        this.reason = reason;
        this.startedAt = startedAt;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public UUID getOrgId() { return orgId; }
    public String getReason() { return reason; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }

    /** Ends the wait. Idempotent so a double resume is not an error the handler must read. */
    public void end(Instant when) {
        if (endedAt == null) this.endedAt = when;
    }
}
