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
        permits MeetingEventPayload.SummaryReady, MeetingEventPayload.ActionAssigned,
        MeetingEventPayload.ConsentRevoked, MeetingEventPayload.TranscriptReady {

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

    /**
     * {@code meeting.consent.revoked} — recording consent was withdrawn.
     *
     * <p>The payload is intentionally thin. Actor identity remains in the durable
     * audit record and is not broadcast to meeting-event consumers.
     *
     * @param captureId capture whose consent was withdrawn; the stable aggregate id
     * @param consentVersion version of the consent text/contract being withdrawn
     * @param consentRevision producer-owned occurrence counter for re-grant/revoke cycles
     * @param reasonCode bounded non-free-text reason code
     */
    record ConsentRevoked(
            UUID captureId,
            String consentVersion,
            long consentRevision,
            String reasonCode) implements MeetingEventPayload {

        @Override
        public MeetingEventType eventType() {
            return MeetingEventType.CONSENT_REVOKED;
        }

        @Override
        public UUID analysisRunId() {
            return null;
        }
    }

    /**
     * {@code meeting.transcript.ready} — one canonical transcript occurrence was
     * explicitly finalized in transcript-service.
     *
     * <p>No transcript or audio content is carried. Consumers use the canonical
     * meeting/session identifiers and version to read the authoritative resource.
     *
     * @param transcriptSessionId canonical {@code meeting_sessions.id}
     * @param finalizationVersion producer-owned occurrence counter; starts at one
     * @param segmentCount number of canonical segments covered by this finalization
     */
    record TranscriptReady(
            UUID transcriptSessionId,
            long finalizationVersion,
            int segmentCount) implements MeetingEventPayload {

        @Override
        public MeetingEventType eventType() {
            return MeetingEventType.TRANSCRIPT_READY;
        }

        @Override
        public UUID analysisRunId() {
            return null;
        }
    }
}
