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
        RecordingFinishedEvent event = parser.parse(validFields());

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
