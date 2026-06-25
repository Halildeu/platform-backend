package com.example.transcript.directstt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectSttTranscriptResultEventTest {

    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void parseMapsAudioGatewayFieldsAndCompanyIdTenantFallback() {
        DirectSttTranscriptResultEvent event =
                DirectSttTranscriptResultEvent.parse(validFields(), "1680000000000-0");

        assertThat(event.entryId()).isEqualTo("1680000000000-0");
        assertThat(event.tenantId()).isEqualTo(
                UUID.nameUUIDFromBytes("company:42".getBytes(StandardCharsets.UTF_8)));
        assertThat(event.sourceTenantId()).isEqualTo("42");
        assertThat(event.sourceUserId()).isEqualTo("7");
        assertThat(event.meetingId()).isEqualTo(MEETING);
        assertThat(event.sourceSessionId()).isEqualTo("SES-abc");
        assertThat(event.chunkSeq()).isEqualTo(5L);
        assertThat(event.chunkStartedAtMs()).isEqualTo(1_250L);
        assertThat(event.textDraft()).isEqualTo("merhaba dunya");
        assertThat(event.durationSeconds()).isEqualTo(1.2d);
    }

    @Test
    void parseRejectsInvalidEventWithMetadataOnlyReason() {
        Map<String, String> fields = validFields();
        fields.put("meetingId", "not-a-uuid");
        fields.put("textDraft", "secret transcript phrase");

        assertThatThrownBy(() -> DirectSttTranscriptResultEvent.parse(fields, "1-0"))
                .isInstanceOf(DirectSttTranscriptResultEvent.InvalidDirectSttTranscriptResultException.class)
                .hasMessageContaining("meetingId")
                .hasMessageNotContaining("secret transcript phrase");
    }

    static Map<String, String> validFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", "audioGateway.directSttTranscriptResult.v1");
        fields.put("eventType", "DIRECT_STT_TRANSCRIPT_RESULT");
        fields.put("sessionId", "SES-abc");
        fields.put("tenantId", "42");
        fields.put("userId", "7");
        fields.put("meetingId", MEETING.toString());
        fields.put("chunkSeq", "5");
        fields.put("chunkStartedAtMs", "1250");
        fields.put("correlationId", "corr-direct-stt");
        fields.put("sha256", "deadbeefcafe0000sha");
        fields.put("textDraft", "merhaba dunya");
        fields.put("textLength", "13");
        fields.put("durationSeconds", "1.2");
        fields.put("status", "DRAFT");
        return fields;
    }
}
