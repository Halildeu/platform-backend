package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.TranscriptEventsResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class DirectSttTranscriptEventReaderTest {

    private StreamOperations<String, Object, Object> streamOps;
    private DirectSttTranscriptEventReader reader;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);

        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDirectStt().getTranscriptResultStream().setStreamKey("transcript:direct-stt-results");
        props.getDirectStt().getTranscriptResultStream().setReadBatchSize(10);
        props.getDirectStt().getTranscriptResultStream().setReadMaxScan(50);
        reader = new DirectSttTranscriptEventReader(redis, props);
    }

    @Test
    void readFiltersBySessionTenantUserAndMapsTranscriptFields() {
        when(streamOps.range(eq("transcript:direct-stt-results"), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(
                        record("1000-0", "SES-other", "42", "7", "other text", "1"),
                        record("1001-0", "SES-abc", "42", "7", "merhaba dunya", "5"),
                        record("1002-0", "SES-abc", "42", "99", "wrong user", "6"),
                        record("1003-0", "SES-abc", "42", "7", "", "7")));

        final TranscriptEventsResponse response = reader.read(session(), null, 10, "corr-1");

        assertThat(response.sessionId()).isEqualTo("SES-abc");
        assertThat(response.correlationId()).isEqualTo("corr-1");
        assertThat(response.nextCursor()).isEqualTo("1003-0");
        assertThat(response.events()).hasSize(1);
        assertThat(response.events().get(0).eventId()).isEqualTo("1001-0");
        assertThat(response.events().get(0).text()).isEqualTo("merhaba dunya");
        assertThat(response.events().get(0).chunkSeq()).isEqualTo(5L);
        assertThat(response.events().get(0).windowSeq()).isEqualTo(2L);
        assertThat(response.events().get(0).firstChunkSeq()).isEqualTo(3L);
        assertThat(response.events().get(0).lastChunkSeq()).isEqualTo(5L);
        assertThat(response.events().get(0).windowStartedAtMs()).isEqualTo(1_250L);
        assertThat(response.events().get(0).windowEndedAtMs()).isEqualTo(2_250L);
        assertThat(response.events().get(0).audioDurationMs()).isEqualTo(1_000);
        assertThat(response.events().get(0).flushReason()).isEqualTo("window_full");
        assertThat(response.events().get(0).meetingId()).isEqualTo("22222222-2222-4222-8222-222222222222");
    }

    @Test
    void readDoesNotSkipMatchingEventsBeyondRequestedLimit() {
        when(streamOps.range(eq("transcript:direct-stt-results"), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(
                        record("1000-0", "SES-abc", "42", "7", "ilk", "1"),
                        record("1001-0", "SES-abc", "42", "7", "ikinci", "2")));

        final TranscriptEventsResponse response = reader.read(session(), "999-0", 1, "corr-2");

        assertThat(response.events()).hasSize(1);
        assertThat(response.events().get(0).eventId()).isEqualTo("1000-0");
        assertThat(response.nextCursor()).isEqualTo("1000-0");
        assertThat(response.hasMore()).isTrue();
    }

    private static SessionRecord session() {
        return new SessionRecord(
                "SES-abc",
                42L,
                7L,
                "22222222-2222-4222-8222-222222222222",
                "desktop-1",
                "tr",
                AudioFormat.PCM16,
                16_000,
                1,
                "start-idempotency-fixture",
                500L,
                SessionState.STREAMING,
                0L,
                1L,
                500L,
                null,
                null,
                null,
                0L,
                500L);
    }

    private static MapRecord<String, Object, Object> record(
            final String id,
            final String sessionId,
            final String tenantId,
            final String userId,
            final String text,
            final String chunkSeq) {
        final Map<Object, Object> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", "audioGateway.directSttTranscriptResult.v1");
        fields.put("eventType", "DIRECT_STT_TRANSCRIPT_RESULT");
        fields.put("sessionId", sessionId);
        fields.put("tenantId", tenantId);
        fields.put("userId", userId);
        fields.put("meetingId", "22222222-2222-4222-8222-222222222222");
        fields.put("chunkSeq", chunkSeq);
        fields.put("chunkStartedAtMs", "1250");
        fields.put("windowSeq", "2");
        fields.put("firstChunkSeq", "3");
        fields.put("lastChunkSeq", chunkSeq);
        fields.put("windowStartedAtMs", "1250");
        fields.put("windowEndedAtMs", "2250");
        fields.put("audioDurationMs", "1000");
        fields.put("flushReason", "window_full");
        fields.put("textDraft", text);
        fields.put("textLength", Integer.toString(text.length()));
        fields.put("status", "DRAFT");
        fields.put("receivedAtMs", "1500");
        fields.put("sttLanguage", "tr");
        return MapRecord.create("transcript:direct-stt-results", fields).withId(RecordId.of(id));
    }
}
