package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.TranscriptResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisStreamDirectSttTranscriptResultSinkTest {

    private StreamOperations<String, Object, Object> streamOps;
    private RedisStreamDirectSttTranscriptResultSink sink;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);

        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDirectStt().setEnabled(true);
        props.getDirectStt().setTranscribeUrl("https://live-stt.denetim:8243/transcribe");
        props.getDirectStt().getTranscriptResultStream().setEnabled(true);
        props.getDirectStt().getTranscriptResultStream().setStreamKey("transcript:direct-stt-results");
        props.getDirectStt().getTranscriptResultStream().setMaxLen(0);

        sink = new RedisStreamDirectSttTranscriptResultSink(redis, props);
    }

    private static DirectSttTranscriptResultContext context() {
        return new DirectSttTranscriptResultContext(
                "SES-abc",
                42L,
                7L,
                5L,
                1_000L,
                2L,
                3L,
                5L,
                1_000L,
                2_000L,
                1_000,
                "window_full",
                "22222222-2222-4222-8222-222222222222",
                "desktop-smoke-1",
                "tr",
                "WAV",
                16_000,
                1,
                "corr-direct-stt",
                "deadbeefcafe0000sha",
                512);
    }

    private static TranscriptResult result() {
        return new TranscriptResult(
                "merhaba dunya",
                "tr",
                0.98,
                1.2,
                345.6,
                "large-v3",
                "int8",
                "cuda",
                null);
    }

    @SuppressWarnings("unchecked")
    @Test
    void emitMapsTranscriptResultToStreamRecord() {
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        sink.emit(result(), context());

        final ArgumentCaptor<MapRecord<String, String, String>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        final MapRecord<String, String, String> record = captor.getValue();
        assertThat(record.getStream()).isEqualTo("transcript:direct-stt-results");
        assertThat(record.getValue())
                .containsEntry("schemaVersion", "audioGateway.directSttTranscriptResult.v1")
                .containsEntry("eventType", "DIRECT_STT_TRANSCRIPT_RESULT")
                .containsEntry("sessionId", "SES-abc")
                .containsEntry("tenantId", "42")
                .containsEntry("userId", "7")
                .containsEntry("meetingId", "22222222-2222-4222-8222-222222222222")
                .containsEntry("deviceId", "desktop-smoke-1")
                .containsEntry("chunkSeq", "5")
                .containsEntry("chunkStartedAtMs", "1000")
                .containsEntry("windowSeq", "2")
                .containsEntry("firstChunkSeq", "3")
                .containsEntry("lastChunkSeq", "5")
                .containsEntry("windowStartedAtMs", "1000")
                .containsEntry("windowEndedAtMs", "2000")
                .containsEntry("audioDurationMs", "1000")
                .containsEntry("flushReason", "window_full")
                .containsEntry("correlationId", "corr-direct-stt")
                .containsEntry("sha256", "deadbeefcafe0000sha")
                .containsEntry("byteLength", "512")
                .containsEntry("requestedLanguage", "tr")
                .containsEntry("audioFormat", "WAV")
                .containsEntry("sampleRateHz", "16000")
                .containsEntry("channels", "1")
                .containsEntry("textDraft", "merhaba dunya")
                .containsEntry("textLength", "13")
                .containsEntry("sttLanguage", "tr")
                .containsEntry("languageProbability", "0.98")
                .containsEntry("durationSeconds", "1.2")
                .containsEntry("elapsedMs", "345.6")
                .containsEntry("model", "large-v3")
                .containsEntry("computeType", "int8")
                .containsEntry("device", "cuda")
                .containsEntry("status", "DRAFT");
        assertThat(record.getValue().get("receivedAtMs")).isNotBlank();
    }

    @SuppressWarnings("unchecked")
    @Test
    void emitUsesApproximateTrimWhenMaxLenIsConfigured() {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        final StreamOperations<String, Object, Object> trimmedOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(trimmedOps);

        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDirectStt().setEnabled(true);
        props.getDirectStt().setTranscribeUrl("https://live-stt.denetim:8243/transcribe");
        props.getDirectStt().getTranscriptResultStream().setEnabled(true);
        props.getDirectStt().getTranscriptResultStream().setStreamKey("transcript:direct-stt-results");
        props.getDirectStt().getTranscriptResultStream().setMaxLen(100);

        final RedisStreamDirectSttTranscriptResultSink trimmedSink =
                new RedisStreamDirectSttTranscriptResultSink(redis, props);
        when(trimmedOps.add(any(MapRecord.class), any(XAddOptions.class))).thenReturn(RecordId.of("1-0"));

        trimmedSink.emit(result(), context());

        verify(trimmedOps).add(any(MapRecord.class), any(XAddOptions.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void emitDoesNotIncludeRawAudioTokensUrlsOrSegments() {
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        sink.emit(result(), context());

        final ArgumentCaptor<MapRecord<String, String, String>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        assertThat(captor.getValue().getValue())
                .doesNotContainKeys("audio", "audioBytes", "bytes", "rawAudio",
                        "authorization", "bearer", "token", "idempotencyKey",
                        "transcribeUrl", "url", "destinationUrl", "segments", "segmentsJson");
    }

    @SuppressWarnings("unchecked")
    @Test
    void emitLetsDataAccessExceptionPropagateForDispatcherMetric() {
        when(streamOps.add(any(MapRecord.class)))
                .thenThrow(new QueryTimeoutException("redis down"));

        assertThatThrownBy(() -> sink.emit(result(), context()))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void emitFailsClosedWhenRedisReturnsNoRecordId() {
        when(streamOps.add(any(MapRecord.class))).thenReturn(null);

        assertThatThrownBy(() -> sink.emit(result(), context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("direct-STT transcript XADD returned no record id");
    }
}
