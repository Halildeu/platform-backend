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
        MeetingEventPayload.ConsentRevoked, MeetingEventPayload.RecordingFinished,
        MeetingEventPayload.TranscriptReady, MeetingEventPayload.TranscriptFailed {

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
     * {@code meeting.recording.finished} — one recording session acquired its first
     * immutable {@code endedAt} value in meeting-service.
     *
     * <p>The payload is intentionally metadata-only: no audio, transcript text,
     * user identity, recording URI or other content-bearing field can be represented.
     *
     * @param recordingSessionId canonical {@code meeting_sessions.id}
     * @param externalSessionId stable audio-gateway session identity
     * @param finishedAt the first persisted recording end timestamp
     */
    record RecordingFinished(
            UUID recordingSessionId,
            String externalSessionId,
            Instant finishedAt) implements MeetingEventPayload {

        @Override
        public MeetingEventType eventType() {
            return MeetingEventType.RECORDING_FINISHED;
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

    /**
     * {@code meeting.transcript.failed} — bounded finalization failed without
     * producing a canonical transcript snapshot.
     *
     * <p>No segment, transcript or audio content is carried. The reason is a bounded
     * machine code rather than free text so the event remains safe to retain/replay.
     *
     * @param transcriptSessionId canonical {@code meeting_sessions.id}
     * @param finalizationVersion producer-owned occurrence counter; starts at one
     * @param reasonCode bounded failure reason
     */
    record TranscriptFailed(
            UUID transcriptSessionId,
            long finalizationVersion,
            String reasonCode) implements MeetingEventPayload {

        public static final String NO_VALID_SEGMENTS_BEFORE_DEADLINE =
                "NO_VALID_SEGMENTS_BEFORE_DEADLINE";
        public static final String INVALID_CANONICAL_SEGMENT =
                "INVALID_CANONICAL_SEGMENT";
        public static final java.util.Set<String> ALLOWED_REASON_CODES = java.util.Set.of(
                NO_VALID_SEGMENTS_BEFORE_DEADLINE,
                INVALID_CANONICAL_SEGMENT);

        @Override
        public MeetingEventType eventType() {
            return MeetingEventType.TRANSCRIPT_FAILED;
        }

        @Override
        public UUID analysisRunId() {
            return null;
        }
    }
}
