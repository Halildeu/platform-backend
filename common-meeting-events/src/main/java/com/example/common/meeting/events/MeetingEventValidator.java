package com.example.common.meeting.events;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared contract check — #802 slice 1, first-PR acceptance 4.
 *
 * <p>Two entry points on purpose. A PRODUCER calls {@link #requireValid} (via
 * {@link MeetingEventEnvelope.Builder#build}) and wants to fail loudly: an event that
 * violates the contract must never reach the outbox, because once committed it will be
 * delivered forever. A CONSUMER calls {@link #validationErrors} and wants to classify:
 * a malformed event has to be reportable and dead-letterable, not an exception that
 * kills the poll loop and stalls every well-formed event behind it.
 *
 * <h2>Conditional required fields</h2>
 * "Required" is per event type, not global. {@code assigneeSubject} is mandatory on
 * {@code action.assigned} — an action event with no assignee is precisely the LLM
 * attribution failure the producer is supposed to drop rather than emit — while
 * {@code summaryGroundingStatus} is optional on {@code summary.ready} and legitimately
 * null. A single global "all fields non-null" rule would get both of these wrong.
 */
public final class MeetingEventValidator {

    private MeetingEventValidator() {
    }

    /** Throws {@link MeetingEventValidationException} on the first violation. */
    public static void requireValid(final MeetingEventEnvelope envelope) {
        final List<String> errors = validationErrors(envelope);
        if (!errors.isEmpty()) {
            throw new MeetingEventValidationException(
                    "Invalid meeting event: " + String.join("; ", errors));
        }
    }

    /** Every violation, empty when the envelope honours the contract. Never throws. */
    public static List<String> validationErrors(final MeetingEventEnvelope envelope) {
        final List<String> errors = new ArrayList<>();
        if (envelope == null) {
            errors.add("envelope is null");
            return errors;
        }

        final MeetingEventPayload payload = envelope.payload();
        if (payload == null) {
            errors.add("payload is required");
        }
        if (envelope.eventType() == null) {
            errors.add("eventType is required");
        }
        // The envelope's declared type and the payload it actually carries must agree;
        // otherwise the serializer would render one type's fields under another's name.
        if (payload != null && envelope.eventType() != null
                && payload.eventType() != envelope.eventType()) {
            errors.add("eventType " + envelope.eventType().wireValue()
                    + " does not match payload type " + payload.eventType().wireValue());
        }

        requireText(errors, envelope.producer(), "producer");
        requireNotNull(errors, envelope.meetingId(), "meetingId");
        // Tenant is the isolation boundary: an event without one cannot be routed or
        // filtered safely, so it is required even though orgId is legitimately null.
        requireNotNull(errors, envelope.tenantId(), "tenantId");
        requireText(errors, envelope.aggregateType(), "aggregateType");
        requireNotNull(errors, envelope.aggregateId(), "aggregateId");

        if (envelope.aggregateRevision() < 0) {
            errors.add("aggregateRevision must be >= 0 but was " + envelope.aggregateRevision());
        }

        if (payload != null) {
            validatePayload(errors, envelope, payload);
            validateEventKey(errors, envelope, payload);
        }
        return errors;
    }

    private static void validatePayload(
            final List<String> errors,
            final MeetingEventEnvelope envelope,
            final MeetingEventPayload payload) {

        switch (payload) {
            case MeetingEventPayload.SummaryReady p -> {
                requireNotNull(errors, p.analysisRunId(), "payload.analysisRunId");
                // Counts describe the run's extracted children; a negative count is not a
                // sparse value but a corrupt one, and consumers size work off these.
                if (p.decisionCount() < 0) {
                    errors.add("payload.decisionCount must be >= 0 but was " + p.decisionCount());
                }
                if (p.actionCount() < 0) {
                    errors.add("payload.actionCount must be >= 0 but was " + p.actionCount());
                }
                // summaryGroundingStatus: optional by design — an ungrounded summary is
                // still a summary, and the consumer decides what to do with an unknown verdict.
            }
            case MeetingEventPayload.ActionAssigned p -> {
                requireNotNull(errors, p.analysisRunId(), "payload.analysisRunId");
                // The attribution guard, enforced at the contract rather than only at the
                // producer: an unassigned action must be DROPPED, never emitted blank.
                if (!MeetingEventKeys.hasText(p.assigneeSubject())) {
                    errors.add("payload.assigneeSubject is required on "
                            + MeetingEventType.ACTION_ASSIGNED.wireValue()
                            + " (an unattributed action must not be emitted)");
                }
                // The ordinal is part of the event key; a negative one would mint a key
                // no other producer could reconstruct.
                if (p.ordinal() < 0) {
                    errors.add("payload.ordinal must be >= 0 but was " + p.ordinal());
                }
            }
            case MeetingEventPayload.ConsentRevoked p -> {
                requireNotNull(errors, p.captureId(), "payload.captureId");
                requireText(errors, p.consentVersion(), "payload.consentVersion");
                requireText(errors, p.reasonCode(), "payload.reasonCode");
                if (p.consentRevision() < 1) {
                    errors.add("payload.consentRevision must be >= 1 but was "
                            + p.consentRevision());
                }
                if (!"USER_WITHDREW".equals(p.reasonCode())) {
                    errors.add("payload.reasonCode must be USER_WITHDREW");
                }
                if (p.consentVersion() != null
                        && !p.consentVersion().matches("[A-Za-z0-9._:-]{1,64}")) {
                    errors.add("payload.consentVersion has invalid format");
                }
                if (envelope.aggregateId() != null
                        && p.captureId() != null
                        && !envelope.aggregateId().equals(p.captureId())) {
                    errors.add("aggregateId " + envelope.aggregateId()
                            + " must equal payload.captureId " + p.captureId()
                            + " for " + p.eventType().wireValue());
                }
                if (envelope.aggregateRevision() != p.consentRevision()) {
                    errors.add("aggregateRevision " + envelope.aggregateRevision()
                            + " must equal payload.consentRevision " + p.consentRevision()
                            + " for " + p.eventType().wireValue());
                }
            }
            case MeetingEventPayload.RecordingFinished p -> {
                requireNotNull(errors, p.recordingSessionId(), "payload.recordingSessionId");
                requireText(errors, p.externalSessionId(), "payload.externalSessionId");
                requireNotNull(errors, p.finishedAt(), "payload.finishedAt");
                if (p.externalSessionId() != null
                        && !p.externalSessionId().matches("[A-Za-z0-9._:-]{1,128}")) {
                    errors.add("payload.externalSessionId has invalid format");
                }
                requireOccurrenceScope(
                        errors, envelope, p.recordingSessionId(), 1,
                        "recordingSessionId", p.eventType());
                if (!"meeting.recording".equals(envelope.aggregateType())) {
                    errors.add("aggregateType must be meeting.recording for "
                            + p.eventType().wireValue());
                }
            }
            case MeetingEventPayload.TranscriptReady p -> {
                requireNotNull(errors, p.transcriptSessionId(), "payload.transcriptSessionId");
                if (p.finalizationVersion() < 1) {
                    errors.add("payload.finalizationVersion must be >= 1 but was "
                            + p.finalizationVersion());
                }
                if (p.segmentCount() < 1) {
                    errors.add("payload.segmentCount must be >= 1 but was " + p.segmentCount());
                }
                if (envelope.aggregateId() != null
                        && p.transcriptSessionId() != null
                        && !envelope.aggregateId().equals(p.transcriptSessionId())) {
                    errors.add("aggregateId " + envelope.aggregateId()
                            + " must equal payload.transcriptSessionId " + p.transcriptSessionId()
                            + " for " + p.eventType().wireValue());
                }
                if (envelope.aggregateRevision() != p.finalizationVersion()) {
                    errors.add("aggregateRevision " + envelope.aggregateRevision()
                            + " must equal payload.finalizationVersion " + p.finalizationVersion()
                            + " for " + p.eventType().wireValue());
                }
            }
            case MeetingEventPayload.TranscriptFailed p -> {
                requireNotNull(errors, p.transcriptSessionId(), "payload.transcriptSessionId");
                if (p.finalizationVersion() < 1) {
                    errors.add("payload.finalizationVersion must be >= 1 but was "
                            + p.finalizationVersion());
                }
                requireText(errors, p.reasonCode(), "payload.reasonCode");
                if (!MeetingEventPayload.TranscriptFailed.ALLOWED_REASON_CODES
                        .contains(p.reasonCode())) {
                    errors.add("payload.reasonCode must be one of "
                            + MeetingEventPayload.TranscriptFailed.ALLOWED_REASON_CODES);
                }
                requireOccurrenceScope(
                        errors, envelope, p.transcriptSessionId(), p.finalizationVersion(),
                        "transcriptSessionId", p.eventType());
                if (!"meeting.transcript".equals(envelope.aggregateType())) {
                    errors.add("aggregateType must be meeting.transcript for "
                            + p.eventType().wireValue());
                }
            }
        }

        // Coherence: for the v1 analysis events the aggregate IS the run. If these ever
        // drift apart, the outbox row and its payload would describe different things.
        if (!(payload instanceof MeetingEventPayload.ConsentRevoked)
                && !(payload instanceof MeetingEventPayload.RecordingFinished)
                && !(payload instanceof MeetingEventPayload.TranscriptReady)
                && !(payload instanceof MeetingEventPayload.TranscriptFailed)
                && envelope.aggregateId() != null
                && payload.analysisRunId() != null
                && !envelope.aggregateId().equals(payload.analysisRunId())) {
            errors.add("aggregateId " + envelope.aggregateId()
                    + " must equal payload.analysisRunId " + payload.analysisRunId()
                    + " for " + payload.eventType().wireValue());
        }
    }

    private static void requireOccurrenceScope(
            final List<String> errors,
            final MeetingEventEnvelope envelope,
            final java.util.UUID payloadAggregateId,
            final long payloadRevision,
            final String payloadIdName,
            final MeetingEventType eventType) {
        if (envelope.aggregateId() != null
                && payloadAggregateId != null
                && !envelope.aggregateId().equals(payloadAggregateId)) {
            errors.add("aggregateId " + envelope.aggregateId()
                    + " must equal payload." + payloadIdName + " " + payloadAggregateId
                    + " for " + eventType.wireValue());
        }
        if (envelope.aggregateRevision() != payloadRevision) {
            errors.add("aggregateRevision " + envelope.aggregateRevision()
                    + " must equal payload revision " + payloadRevision
                    + " for " + eventType.wireValue());
        }
    }

    private static void validateEventKey(
            final List<String> errors,
            final MeetingEventEnvelope envelope,
            final MeetingEventPayload payload) {

        if (!MeetingEventKeys.hasText(envelope.eventKey())) {
            errors.add("eventKey is required");
            return;
        }
        // Determinism is the whole idempotency guarantee, so it is checked rather than
        // trusted: a caller-supplied key that does not match what the factory derives
        // (e.g. an envelope rehydrated from a row written by older code) is drift, and
        // silently honouring it would break exactly-once de-duplication downstream.
        final String derived;
        try {
            derived = MeetingEventKeys.forPayload(payload);
        } catch (MeetingEventValidationException e) {
            // The payload is already reported as invalid above; no second complaint.
            return;
        }
        if (!derived.equals(envelope.eventKey())) {
            errors.add("eventKey '" + envelope.eventKey() + "' is not the deterministic key '"
                    + derived + "' for this payload");
        }
    }

    private static void requireNotNull(final List<String> errors, final Object value, final String field) {
        if (value == null) {
            errors.add(field + " is required");
        }
    }

    private static void requireText(final List<String> errors, final String value, final String field) {
        if (!MeetingEventKeys.hasText(value)) {
            errors.add(field + " is required");
        }
    }
}
