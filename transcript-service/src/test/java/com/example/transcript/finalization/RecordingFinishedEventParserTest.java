package com.example.transcript.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingFinishedEventParserTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEETING = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final RecordingFinishedEventParser parser = new RecordingFinishedEventParser(new ObjectMapper());

    @Test
    void parsesFrozenWireAndDerivesContentHash() {
        RecordingFinishedEvent event = (RecordingFinishedEvent) parser.parse(validFields());

        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.meetingId()).isEqualTo(MEETING);
        assertThat(event.recordingSessionId()).isEqualTo(SESSION);
        assertThat(event.externalSessionId()).isEqualTo("SES-desktop-1");
        assertThat(event.finishedAt()).isEqualTo(Instant.parse("2026-07-17T10:05:00Z"));
        assertThat(event.eventKey()).isEqualTo(
                "meeting.recording|" + SESSION + "|meeting.recording.finished|1");
        assertThat(event.payloadSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void validUnownedEventIsIgnoredWithoutReadingPayload() {
        assertThat(parser.parse(Map.of("eventType", "meeting.created"))).isNull();
    }

    @Test
    void payloadShapeAndOuterScopeDivergenceFailClosed() {
        Map<String, String> extraPayloadField = validFields();
        extraPayloadField.put("payload", extraPayloadField.get("payload")
                .replace("}", ",\"transcriptText\":\"must-not-be-accepted\"}"));
        assertThatThrownBy(() -> parser.parse(extraPayloadField))
                .isInstanceOf(RecordingFinishedEventParser.RecordingFinishedEventInvalidException.class)
                .hasMessage("PAYLOAD_SHAPE");

        Map<String, String> wrongTenant = validFields();
        wrongTenant.put("tenantId", "00000000-0000-0000-0000-000000000099");
        assertThatThrownBy(() -> parser.parse(wrongTenant))
                .isInstanceOf(RecordingFinishedEventParser.RecordingFinishedEventInvalidException.class)
                .hasMessage("OUTER_TENANTID");
    }

    @Test
    void oversizedPayloadFailsBeforeJsonParsing() {
        Map<String, String> fields = validFields();
        fields.put("payload", "x".repeat(RecordingFinishedEventParser.MAX_PAYLOAD_UTF8_BYTES + 1));

        assertThatThrownBy(() -> parser.parse(fields))
                .isInstanceOf(RecordingFinishedEventParser.RecordingFinishedEventInvalidException.class)
                .hasMessage("PAYLOAD_TOO_LARGE");
    }

    @Test
    void rejectsAProducerValueOutsideTheSharedSesContract() {
        Map<String, String> fields = validFields();
        fields.put("payload", fields.get("payload")
                .replace("SES-desktop-1", "room-1"));

        assertThatThrownBy(() -> parser.parse(fields))
                .isInstanceOf(RecordingFinishedEventParser.RecordingFinishedEventInvalidException.class)
                .hasMessage("SOURCE_SESSION_FORMAT");
    }

    @Test
    void rejectsUnsafeSourceSessionCharacters() {
        Map<String, String> fields = validFields();
        fields.put("payload", fields.get("payload")
                .replace("SES-desktop-1", "../foreign"));

        assertThatThrownBy(() -> parser.parse(fields))
                .isInstanceOf(RecordingFinishedEventParser.RecordingFinishedEventInvalidException.class)
                .hasMessage("SOURCE_SESSION_FORMAT");
    }

    @Test
    void parsesActualProducerIncompleteWireAsDistinctClosure() {
        var event = (RecordingIncompleteEvent) parser.parse(incompleteFields());
        assertThat(event.eventType()).isEqualTo("meeting.recording.incomplete");
        assertThat(event.eventKey()).isEqualTo("meeting.recording|" + SESSION + "|meeting.recording.incomplete|1");
        assertThat(event.closedAt()).isEqualTo(Instant.parse("2026-07-17T10:05:00.123456Z"));
        assertThat(event.reasonCode()).isEqualTo("CLOSURE_UNCONFIRMED");
        assertThat(event.recordingSessionId()).isEqualTo(SESSION);
        assertThat(event.payloadSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void incompleteRejectsWrongReasonSubmicrosecondTimeAndMismatchedGeneratedTime() {
        for (String replacement : new String[]{
                incompleteFields().get("payload").replace("CLOSURE_UNCONFIRMED", "UNKNOWN"),
                incompleteFields().get("payload").replace(".123456Z", ".123456789Z"),
                incompleteFields().get("payload").replaceFirst("10:05:00", "10:05:01")}) {
            Map<String, String> fields = incompleteFields();
            fields.put("payload", replacement);
            assertThatThrownBy(() -> parser.parse(fields))
                    .isInstanceOf(RecordingFinishedEventParser.RecordingFinishedEventInvalidException.class);
        }
    }

    @Test
    void incompleteCannotMasqueradeAsFinishedOrChangeOuterScope() {
        Map<String, String> fields = incompleteFields();
        fields.put("eventType", "meeting.recording.finished");
        assertThatThrownBy(() -> parser.parse(fields))
                .isInstanceOf(RecordingFinishedEventParser.RecordingFinishedEventInvalidException.class);
        fields.put("eventType", "meeting.recording.incomplete");
        fields.put("eventKey", "meeting.recording|" + SESSION + "|meeting.recording.finished|1");
        assertThatThrownBy(() -> parser.parse(fields))
                .isInstanceOf(RecordingFinishedEventParser.RecordingFinishedEventInvalidException.class)
                .hasMessage("OUTER_EVENTKEY");
    }

    private Map<String, String> incompleteFields() {
        var time = Instant.parse("2026-07-17T10:05:00.123456Z");
        var envelope = com.example.common.meeting.events.MeetingEventEnvelope.builder()
                .eventType(com.example.common.meeting.events.MeetingEventType.RECORDING_INCOMPLETE)
                .producer("meeting-service").tenantId(TENANT).orgId(TENANT).meetingId(MEETING)
                .aggregateType("meeting.recording").aggregateId(SESSION).aggregateRevision(1L)
                .occurredAt(time)
                .payload(new com.example.common.meeting.events.MeetingEventPayload.RecordingIncomplete(
                        SESSION, "SES-desktop-1", time, "CLOSURE_UNCONFIRMED"))
                .build();
        var fields = validFields();
        fields.put("eventType", "meeting.recording.incomplete");
        fields.put("eventKey", envelope.eventKey());
        fields.put("payload", com.example.common.meeting.events.MeetingEventV1Serializer.toJson(envelope));
        return fields;
    }

    private Map<String, String> validFields() {
        String payload = "{"
                + "\"schema\":\"meeting.event.v1\","
                + "\"eventType\":\"meeting.recording.finished\","
                + "\"analysisRunId\":null,"
                + "\"meetingId\":\"" + MEETING + "\","
                + "\"tenantId\":\"" + TENANT + "\","
                + "\"orgId\":\"" + TENANT + "\","
                + "\"generatedAt\":\"2026-07-17T10:05:01Z\","
                + "\"recordingSessionId\":\"" + SESSION + "\","
                + "\"externalSessionId\":\"SES-desktop-1\","
                + "\"finishedAt\":\"2026-07-17T10:05:00Z\""
                + "}";
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", "meeting.recording.finished");
        fields.put("aggregateType", "meeting.recording");
        fields.put("aggregateId", SESSION.toString());
        fields.put("aggregateRevision", "1");
        fields.put("meetingId", MEETING.toString());
        fields.put("tenantId", TENANT.toString());
        fields.put("orgId", TENANT.toString());
        fields.put("eventKey", "meeting.recording|" + SESSION + "|meeting.recording.finished|1");
        fields.put("payload", payload);
        return fields;
    }
}
