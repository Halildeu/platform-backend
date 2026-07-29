package com.example.ethics.service;

import com.example.ethics.config.EthicsSlaProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ES-301 — when each case was due an answer, and whether it got one.
 *
 * <p>The deadline used to be computed in the browser from {@code createdAt}. That made it
 * real only while a case was open on screen: it could not be queried, alerted on, or
 * counted, and two clients could disagree. On the test cell 49 of 167 cases were already
 * past the seven-day acknowledgement mark and no surface said so.
 *
 * <p>The states are deliberately three, not two. "Not acknowledged" collapses two
 * different situations — still inside the window, and past it — and only the second is a
 * breach. Reading a list where both look the same is how a handler concludes there is
 * nothing urgent.
 */
@Component
public class CaseSlaClock {

    private final EthicsSlaProperties properties;
    private final Clock clock;

    @Autowired
    public CaseSlaClock(EthicsSlaProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Package-private so a test can fix the instant; there is no Clock bean to override. */
    CaseSlaClock(EthicsSlaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Where a case stands against the obligation to acknowledge receipt. */
    public enum AcknowledgementState {
        /** Acknowledged. Whether it was late is a separate fact — see {@link #wasLate}. */
        MET,
        /** Not acknowledged, still inside the window. */
        PENDING,
        /** Not acknowledged and the window has passed. */
        BREACHED,
        /**
         * The case carries no creation time, so nothing can be computed. Reported rather
         * than defaulted: a case shown as PENDING because its timestamp was missing reads
         * as "fine" and is the one that never gets chased.
         */
        UNKNOWN
    }

    public record Acknowledgement(AcknowledgementState state, Instant dueAt, boolean wasLate) {}

    /**
     * Where a case stands against the obligation to give the reporter feedback.
     *
     * <p>Modelled on closure rather than on any staff message, and the distinction matters.
     * Closing a case requires a reporter-facing closing message — staff-authored prose about
     * what was found — so a closed case is a case where the reporter was told the outcome.
     * A mid-investigation note is contact, not feedback on action taken.
     *
     * <p>This is the conservative reading: it can report a breach for a case whose reporter
     * has in fact been kept informed, but it cannot report compliance for one who has heard
     * nothing. Between over- and under-reporting a legal obligation, only one direction is
     * safe.
     */
    public Feedback feedback(Instant createdAt, Instant closedAt) {
        if (createdAt == null) {
            return new Feedback(FeedbackState.UNKNOWN, null, false);
        }
        Instant dueAt = createdAt.plus(properties.feedbackWithin());
        if (closedAt != null) {
            return new Feedback(FeedbackState.MET, dueAt, closedAt.isAfter(dueAt));
        }
        return clock.instant().isAfter(dueAt)
                ? new Feedback(FeedbackState.BREACHED, dueAt, false)
                : new Feedback(FeedbackState.PENDING, dueAt, false);
    }

    /** Mirrors {@link AcknowledgementState}; the two obligations are separate and can differ. */
    public enum FeedbackState { MET, PENDING, BREACHED, UNKNOWN }

    public record Feedback(FeedbackState state, Instant dueAt, boolean wasLate) {}

    public Acknowledgement acknowledgement(Instant createdAt, Instant acknowledgedAt) {
        if (createdAt == null) {
            return new Acknowledgement(AcknowledgementState.UNKNOWN, null, false);
        }
        Instant dueAt = createdAt.plus(properties.acknowledgementWithin());
        if (acknowledgedAt != null) {
            // A late acknowledgement still satisfies the obligation to acknowledge; it does
            // not un-happen. Recording that it was late keeps the two facts separable
            // instead of hiding the delay behind a green state.
            return new Acknowledgement(AcknowledgementState.MET, dueAt, acknowledgedAt.isAfter(dueAt));
        }
        return clock.instant().isAfter(dueAt)
                ? new Acknowledgement(AcknowledgementState.BREACHED, dueAt, false)
                : new Acknowledgement(AcknowledgementState.PENDING, dueAt, false);
    }
}
