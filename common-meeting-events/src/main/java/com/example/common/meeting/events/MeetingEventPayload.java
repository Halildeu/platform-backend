package com.example.common.meeting.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The per-event-type payload — the fields that are specific to one event and are
 * NOT part of the shared envelope core.
 *
 * <p>Sealed so the serializer's switch is exhaustive at compile time: adding a new
 * event type cannot silently fall through to a default branch and emit a half-built
 * event.
 *
 * <p><b>Why {@code analysisRunId} lives here and not on the envelope</b> (#802 owner
 * decision): it is meaningful only for the analysis-derived events. The later
 * {@code consent.revoked} / {@code transcript.ready} producers have no analysis run,
 * so promoting it to the envelope would force every producer to carry a field it
 * cannot fill. It stays physically in the {@code meeting.event.v1} wire exactly where
 * it is today — see {@link MeetingEventV1Serializer}.
 */
public sealed interface MeetingEventPayload
        permits MeetingEventPayload.SummaryReady, MeetingEventPayload.ActionAssigned {

    /** The event type this payload belongs to — cross-checked against the envelope. */
    MeetingEventType eventType();

    /** The analysis run this event derives from; also the aggregate id for v1 types. */
    UUID analysisRunId();

    /**
     * {@code meeting.summary.ready} — the run's summary is durably stored.
     *
     * @param analysisRunId          the run this summary belongs to
     * @param summaryGroundingStatus grounding verdict; nullable, rendered as JSON null
     * @param decisionCount          number of decisions extracted in the run
     * @param actionCount            number of actions extracted in the run
     */
    record SummaryReady(
            UUID analysisRunId,
            String summaryGroundingStatus,
            int decisionCount,
            int actionCount) implements MeetingEventPayload {

        @Override
        public MeetingEventType eventType() {
            return MeetingEventType.SUMMARY_READY;
        }
    }

    /**
     * {@code meeting.action.assigned} — an AI-extracted action has a real assignee.
     *
     * @param analysisRunId   the run this action belongs to
     * @param ordinal         the action's position within the run; part of the event key
     * @param assigneeSubject the attributed assignee; never null/blank for an emitted event
     * @param dueAt           optional due date; nullable, rendered as JSON null
     */
    record ActionAssigned(
            UUID analysisRunId,
            int ordinal,
            String assigneeSubject,
            Instant dueAt) implements MeetingEventPayload {

        @Override
        public MeetingEventType eventType() {
            return MeetingEventType.ACTION_ASSIGNED;
        }
    }
}
