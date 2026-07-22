package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.TranscriptResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis result-stream sink for direct-STT transcript handoff.
 *
 * <p>This writes transcript text after live-stt returns it, so it is a transcript
 * persistence/handoff surface, not an audio stream. It never writes raw audio
 * bytes, bearer tokens, auth headers, destination URLs, or segment JSON.
 */
@Service
@ConditionalOnProperty(
        name = "audio.gateway.direct-stt.transcript-result-stream.enabled",
        havingValue = "true")
public class RedisStreamDirectSttTranscriptResultSink implements DirectSttTranscriptResultSink {

    static final String EVENT_TYPE_DIRECT_STT_TRANSCRIPT_RESULT = "DIRECT_STT_TRANSCRIPT_RESULT";
    static final String SCHEMA_VERSION = "audioGateway.directSttTranscriptResult.v1";

    private final StringRedisTemplate redis;
    private final AudioGatewayProperties.DirectStt.TranscriptResultStream cfg;

    public RedisStreamDirectSttTranscriptResultSink(final StringRedisTemplate redis,
                                                   final AudioGatewayProperties properties) {
        this.redis = redis;
        this.cfg = properties.getDirectStt().getTranscriptResultStream();
    }

    @Override
    public void emit(final TranscriptResult result, final DirectSttTranscriptResultContext context) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", SCHEMA_VERSION);
        fields.put("eventType", EVENT_TYPE_DIRECT_STT_TRANSCRIPT_RESULT);
        fields.put("sessionId", nullSafe(context.sessionId()));
        fields.put("tenantId", longOrEmpty(context.tenantId()));
        fields.put("userId", longOrEmpty(context.userId()));
        fields.put("meetingId", nullSafe(context.meetingId()));
        fields.put("deviceId", nullSafe(context.deviceId()));
        fields.put("chunkSeq", Long.toString(context.chunkSeq()));
        fields.put("chunkStartedAtMs", Long.toString(context.chunkStartedAtMs()));
        fields.put("windowSeq", Long.toString(context.windowSeq()));
        // Ordering key: windowSeq restarts per sequence space, so a consumer that wants
        // chronological order must sort on (transportEpoch, windowSeq), not windowSeq.
        fields.put("transportEpoch", Long.toString(context.transportEpoch()));
        fields.put("firstChunkSeq", Long.toString(context.firstChunkSeq()));
        fields.put("lastChunkSeq", Long.toString(context.lastChunkSeq()));
        fields.put("windowStartedAtMs", Long.toString(context.windowStartedAtMs()));
        fields.put("windowEndedAtMs", Long.toString(context.windowEndedAtMs()));
        fields.put("audioDurationMs", Integer.toString(context.audioDurationMs()));
        fields.put("flushReason", nullSafe(context.flushReason()));
        fields.put("correlationId", nullSafe(context.correlationId()));
        fields.put("sha256", nullSafe(context.sha256()));
        fields.put("byteLength", Integer.toString(context.byteLength()));
        fields.put("requestedLanguage", nullSafe(context.requestedLanguage()));
        fields.put("audioFormat", nullSafe(context.audioFormat()));
        fields.put("sampleRateHz", Integer.toString(context.sampleRateHz()));
        fields.put("channels", Integer.toString(context.channels()));
        fields.put("textDraft", nullSafe(result.text()));
        fields.put("textLength", Integer.toString(result.textLength()));
        fields.put("sttLanguage", nullSafe(result.language()));
        fields.put("languageProbability", doubleOrEmpty(result.languageProbability()));
        fields.put("durationSeconds", doubleOrEmpty(result.durationSeconds()));
        fields.put("elapsedMs", doubleOrEmpty(result.elapsedMs()));
        fields.put("model", nullSafe(result.model()));
        fields.put("computeType", nullSafe(result.computeType()));
        fields.put("device", nullSafe(result.device()));
        // Segment JSON intentionally stays out of the stream for the first #182 handoff:
        // it carries transcript content plus per-word timing and can be added later under
        // an explicit schema bump if downstream assembly needs that fidelity.
        final DirectSttTranscriptResultContext.Assembly assembly = context.assembly();
        // A raw committed chunk stays DRAFT. An assembled line is a distinct status so a
        // client can render the readable line without also rendering the fragments it
        // was folded from — both remain on the stream, nothing is replaced.
        fields.put("status", assembly == null ? "DRAFT" : "UTTERANCE");
        if (assembly != null) {
            fields.put("assemblyReason", nullSafe(assembly.reason()));
            fields.put("sourceEventIds", String.join(",", assembly.sourceEventIds()));
        }
        fields.put("receivedAtMs", Long.toString(System.currentTimeMillis()));

        final MapRecord<String, String, String> record =
                StreamRecords.mapBacked(fields).withStreamKey(cfg.getStreamKey());
        final RecordId recordId;
        if (cfg.getMaxLen() > 0) {
            recordId = redis.opsForStream().add(record,
                    XAddOptions.maxlen(cfg.getMaxLen()).approximateTrimming(true));
        } else {
            recordId = redis.opsForStream().add(record);
        }
        if (recordId == null) {
            throw new IllegalStateException("direct-STT transcript XADD returned no record id");
        }
    }

    private static String nullSafe(final String value) {
        return value == null ? "" : value;
    }

    private static String longOrEmpty(final Long value) {
        return value == null ? "" : Long.toString(value);
    }

    private static String doubleOrEmpty(final Double value) {
        return value == null ? "" : value.toString();
    }
}
