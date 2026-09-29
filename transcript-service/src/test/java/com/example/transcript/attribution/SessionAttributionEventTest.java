package com.example.transcript.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** #3746 BE-D4b — strict parse of directSttSessionAttribution.v1. */
class SessionAttributionEventTest {

    private static final String MEETING = "9b2c5a89-f39a-47da-a33d-a2859b468e8b";

    private static String valid() {
        return """
                {"schema":"directSttSessionAttribution.v1","tenantId":"42",
                 "meetingId":"%s","sourceSessionId":"SES-1","transportEpoch":3,
                 "model":"pyannote/speaker-diarization-3.1",
                 "modelRevision":"84fd25912480287da0247647c3d2b4853cb3ee5d",
                 "audioSampleCount":160000,"generatedAt":"2026-09-29T13:00:00Z",
                 "windows":[
                   {"windowSeq":0,"speaker":"SPEAKER_00","startMs":0,"endMs":5000,
                    "dominanceRatio":0.92},
                   {"windowSeq":2,"speaker":"UU","startMs":5000,"endMs":9000,
                    "dominanceRatio":0.51}]}
                """.formatted(MEETING);
    }

    @Test
    void parsesCanonicalEvent() {
        final SessionAttributionEvent event = SessionAttributionEvent.parse(valid());
        assertThat(event.sourceTenantId()).isEqualTo("42");
        // Numeric tenants map exactly like the direct-STT ingest ("company:" + id).
        assertThat(event.tenantId()).isEqualTo(
                UUID.nameUUIDFromBytes("company:42".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(event.meetingId()).isEqualTo(UUID.fromString(MEETING));
        assertThat(event.transportEpoch()).isEqualTo(3L);
        assertThat(event.windows()).hasSize(2);
        assertThat(event.windows().get(1).unknown()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"schema\":\"directSttSessionAttribution.v1\"|\"schema\":\"directSttSessionAttribution.v2\"",
        "\"model\":\"pyannote/speaker-diarization-3.1\"|\"model\":\"pyannote/speaker-diarization-3.0\"",
        "\"speaker\":\"SPEAKER_00\"|\"speaker\":\"ALICE\"",
        "\"audioSampleCount\":160000|\"audioSampleCount\":100",
        "\"windowSeq\":2|\"windowSeq\":0",
        "\"transportEpoch\":3|\"transportEpoch\":-1",
        "\"meetingId\":\"9b2c5a89-f39a-47da-a33d-a2859b468e8b\"|\"meetingId\":\"not-a-uuid\"",
    })
    void rejectsContractViolations(final String replacement) {
        final String[] parts = replacement.split("\\|");
        final String payload = valid().replace(parts[0], parts[1]);
        assertThatThrownBy(() -> SessionAttributionEvent.parse(payload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownFieldsAtBothLevels() {
        assertThatThrownBy(() -> SessionAttributionEvent.parse(
                valid().replace("\"tenantId\"", "\"extra\":1,\"tenantId\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field");
        assertThatThrownBy(() -> SessionAttributionEvent.parse(
                valid().replace("\"dominanceRatio\":0.92", "\"dominanceRatio\":0.92,\"text\":\"x\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown window field");
    }

    @Test
    void rejectsMissingEmptyOrNonJsonPayload() {
        assertThatThrownBy(() -> SessionAttributionEvent.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionAttributionEvent.parse("not json"))
                .isInstanceOf(IllegalArgumentException.class);
        final String emptyWindows = """
                {"schema":"directSttSessionAttribution.v1","tenantId":"42",
                 "meetingId":"%s","sourceSessionId":"SES-1","transportEpoch":3,
                 "model":"pyannote/speaker-diarization-3.1",
                 "modelRevision":"84fd25912480287da0247647c3d2b4853cb3ee5d",
                 "audioSampleCount":160000,"generatedAt":"2026-09-29T13:00:00Z",
                 "windows":[]}
                """.formatted(MEETING);
        assertThatThrownBy(() -> SessionAttributionEvent.parse(emptyWindows))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
