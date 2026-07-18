package com.example.common.meeting.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Renders an envelope as {@code meeting.event.v1} bytes — #802 slice 1.
 *
 * <p>These bytes are FROZEN (#802 first-PR acceptance 2). Every field name, the field
 * ORDER, and the explicit {@code null}s are the contract as it exists in production
 * today, verified against golden fixtures captured from the pre-refactor producer. A
 * consumer pinned to v1 parses exactly this. Anything structural — moving
 * {@code analysisRunId} under a {@code data} object, adding {@code producer},
 * {@code aggregateType} or {@code aggregateRevision}, renaming {@code generatedAt} to
 * {@code occurredAt} — is {@code meeting.event.v2}, reached by consumer dual-read then
 * producer switch then v1 retirement, and never by dual-publishing the same logical
 * event as both v1 and v2.
 *
 * <h2>Why this owns its ObjectMapper</h2>
 * The pre-refactor factory serialized with whichever {@code ObjectMapper} its service
 * injected. That made the wire format a function of a SHARED, service-owned bean: had
 * any service ever set {@code NON_NULL} inclusion, {@code "orgId":null} would silently
 * vanish from the bytes and every pinned consumer would start seeing an absent field
 * instead of a null one — a wire break with no code change anywhere near this class.
 * The v1 format is not negotiable per service, so the writer is not injectable. The
 * bytes are identical to today's; only the ability to accidentally change them is gone.
 */
public final class MeetingEventV1Serializer {

    /** Payload schema tag — lets a consumer pin the contract it parses. */
    public static final String SCHEMA = "meeting.event.v1";

    /**
     * The canonical writer. {@code ALWAYS} inclusion is load-bearing, not a default
     * being restated: it is what keeps nullable fields present-as-null on the wire.
     */
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .build();

    private MeetingEventV1Serializer() {
    }

    /**
     * The v1 JSON for an envelope.
     *
     * <p>Values are rendered as strings and ints only — never as Jackson-serialized
     * {@code UUID}/{@code Instant} objects — so the bytes cannot shift with a mapper's
     * date or type handling.
     */
    public static String toJson(final MeetingEventEnvelope envelope) {
        MeetingEventValidator.requireValid(envelope);
        return write(fields(envelope));
    }

    /**
     * The ordered v1 field map. Exposed for the golden fixtures and for a producer that
     * needs the fields without the bytes; the ORDER of the puts below is the wire order.
     */
    static Map<String, Object> fields(final MeetingEventEnvelope envelope) {
        final MeetingEventPayload payload = envelope.payload();

        // The v1 envelope prefix, identical for every event type.
        final Map<String, Object> json = new LinkedHashMap<>();
        json.put("schema", SCHEMA);
        json.put("eventType", envelope.eventType().wireValue());
        // analysisRunId is a typed-payload field (#802 owner decision) that v1 happens to
        // render up here among the envelope fields. Its POSITION is legacy and frozen; its
        // OWNERSHIP is the payload's. Reading it from the payload rather than from
        // envelope.aggregateId() is what keeps the decision honest — the validator proves
        // the two agree, so this is a statement about which one is the source of truth.
        json.put("analysisRunId", text(payload.analysisRunId()));
        json.put("meetingId", text(envelope.meetingId()));
        json.put("tenantId", text(envelope.tenantId()));
        json.put("orgId", text(envelope.orgId()));
        json.put("generatedAt", text(envelope.occurredAt()));

        // The per-type suffix.
        switch (payload) {
            case MeetingEventPayload.SummaryReady p -> {
                json.put("summaryGroundingStatus", p.summaryGroundingStatus());
                json.put("decisionCount", p.decisionCount());
                json.put("actionCount", p.actionCount());
            }
            case MeetingEventPayload.ActionAssigned p -> {
                json.put("ordinal", p.ordinal());
                json.put("assigneeSubject", p.assigneeSubject());
                json.put("dueAt", text(p.dueAt()));
            }
            case MeetingEventPayload.ConsentRevoked p -> {
                json.put("captureId", text(p.captureId()));
                json.put("consentVersion", p.consentVersion());
                json.put("consentRevision", p.consentRevision());
                json.put("reasonCode", p.reasonCode());
            }
            case MeetingEventPayload.RecordingFinished p -> {
                json.put("recordingSessionId", text(p.recordingSessionId()));
                json.put("externalSessionId", p.externalSessionId());
                json.put("finishedAt", text(p.finishedAt()));
            }
            case MeetingEventPayload.TranscriptReady p -> {
                json.put("transcriptSessionId", text(p.transcriptSessionId()));
                json.put("finalizationVersion", p.finalizationVersion());
                json.put("segmentCount", p.segmentCount());
            }
            case MeetingEventPayload.TranscriptFailed p -> {
                json.put("transcriptSessionId", text(p.transcriptSessionId()));
                json.put("finalizationVersion", p.finalizationVersion());
                json.put("reasonCode", p.reasonCode());
            }
        }
        return json;
    }

    private static String text(final UUID value) {
        return value == null ? null : value.toString();
    }

    private static String text(final Instant value) {
        return value == null ? null : value.toString();
    }

    private static String write(final Map<String, Object> json) {
        try {
            return CANONICAL.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise meeting event payload.", e);
        }
    }
}
