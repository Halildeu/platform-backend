package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.TranscriptEventResponse;
import com.example.audiogateway.dto.TranscriptEventsResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Authenticated session-scoped read bridge over the direct-STT Redis result
 * stream. This is a delivery surface for transcript text, not raw audio.
 */
@Service
public class DirectSttTranscriptEventReader {

    private static final String SCHEMA_VERSION = "audioGateway.directSttTranscriptResult.v1";
    private static final String EVENT_TYPE = "DIRECT_STT_TRANSCRIPT_RESULT";
    private static final long SESSION_START_LOOKBACK_MS = 5_000L;

    private final StringRedisTemplate redis;
    private final AudioGatewayProperties.DirectStt.TranscriptResultStream cfg;

    public DirectSttTranscriptEventReader(final StringRedisTemplate redis,
                                          final AudioGatewayProperties properties) {
        this.redis = redis;
        this.cfg = properties.getDirectStt().getTranscriptResultStream();
    }

    public TranscriptEventsResponse read(final SessionRecord session,
                                         final String afterCursor,
                                         final int requestedLimit,
                                         final String correlationId) {
        final int eventLimit = clamp(requestedLimit, 1, cfg.getReadBatchSize());
        final int scanLimit = Math.max(eventLimit, cfg.getReadMaxScan());
        final String lowerBound = lowerBound(session, afterCursor);
        final Range<String> range = Range.rightUnbounded(Range.Bound.exclusive(lowerBound));
        final Limit limit = Limit.limit().count(scanLimit);

        final StreamOperations<String, Object, Object> ops = redis.opsForStream();
        final List<MapRecord<String, Object, Object>> records = ops.range(cfg.getStreamKey(), range, limit);
        if (records == null || records.isEmpty()) {
            return new TranscriptEventsResponse(session.sessionId(), correlationId, List.of(), lowerBound, false);
        }

        final List<TranscriptEventResponse> events = new ArrayList<>();
        String lastScannedId = lowerBound;
        for (MapRecord<String, Object, Object> record : records) {
            final String recordId = record.getId() == null ? "" : record.getId().getValue();
            if (!recordId.isBlank()) {
                lastScannedId = recordId;
            }
            final TranscriptEventResponse event = mapIfVisibleToSession(record, session);
            if (event == null) {
                continue;
            }
            events.add(event);
            if (events.size() >= eventLimit) {
                return new TranscriptEventsResponse(
                        session.sessionId(), correlationId, List.copyOf(events), event.eventId(), true);
            }
        }

        return new TranscriptEventsResponse(
                session.sessionId(),
                correlationId,
                List.copyOf(events),
                lastScannedId,
                records.size() >= scanLimit);
    }

    private TranscriptEventResponse mapIfVisibleToSession(
            final MapRecord<String, Object, Object> record,
            final SessionRecord session) {
        final Map<String, String> fields = toStringFields(record);
        if (!SCHEMA_VERSION.equals(fields.get("schemaVersion"))
                || !EVENT_TYPE.equals(fields.get("eventType"))
                || !session.sessionId().equals(fields.get("sessionId"))
                || !Long.toString(session.tenantId()).equals(fields.get("tenantId"))
                || !Long.toString(session.userId()).equals(fields.get("userId"))) {
            return null;
        }

        final String text = blankToNull(fields.get("textDraft"));
        if (text == null) {
            return null;
        }

        final String recordId = record.getId() == null ? "" : record.getId().getValue();
        final Long chunkSeq = parseLong(fields.get("chunkSeq"));
        final Long chunkStartedAtMs = parseLong(fields.get("chunkStartedAtMs"));
        if (recordId.isBlank() || chunkSeq == null || chunkSeq < 0
                || chunkStartedAtMs == null || chunkStartedAtMs < 0) {
            return null;
        }

        final Integer declaredTextLength = parseInt(fields.get("textLength"));
        final long windowSeq = defaultLong(parseLong(fields.get("windowSeq")), chunkSeq);
        final long firstChunkSeq = defaultLong(parseLong(fields.get("firstChunkSeq")), chunkSeq);
        final long lastChunkSeq = defaultLong(parseLong(fields.get("lastChunkSeq")), chunkSeq);
        final long windowStartedAtMs =
                defaultLong(parseLong(fields.get("windowStartedAtMs")), chunkStartedAtMs);
        final int audioDurationMs = defaultInt(parseInt(fields.get("audioDurationMs")), 0);
        final long windowEndedAtMs = defaultLong(
                parseLong(fields.get("windowEndedAtMs")),
                windowStartedAtMs + audioDurationMs);
        return new TranscriptEventResponse(
                recordId,
                session.sessionId(),
                fields.getOrDefault("meetingId", session.meetingId()),
                chunkSeq,
                chunkStartedAtMs,
                windowSeq,
                firstChunkSeq,
                lastChunkSeq,
                windowStartedAtMs,
                windowEndedAtMs,
                audioDurationMs,
                defaultIfBlank(fields.get("flushReason"), "chunk"),
                text,
                declaredTextLength == null ? text.length() : declaredTextLength,
                defaultIfBlank(fields.get("status"), "DRAFT"),
                parseLong(fields.get("receivedAtMs")),
                blankToNull(fields.get("sttLanguage")),
                parseDouble(fields.get("durationSeconds")),
                blankToNull(fields.get("correlationId")),
                blankToNull(fields.get("assemblyReason")),
                splitIds(fields.get("sourceEventIds")),
                defaultLong(parseLong(fields.get("transportEpoch")), 0L));
    }

    /** Comma-joined source ids of an assembled line; empty for a raw chunk. */
    private static List<String> splitIds(final String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(","))
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .toList();
    }

    private static long defaultLong(final Long value, final long fallback) {
        return value == null ? fallback : value;
    }

    private static int defaultInt(final Integer value, final int fallback) {
        return value == null ? fallback : value;
    }

    private static Map<String, String> toStringFields(final MapRecord<String, Object, Object> record) {
        final Map<String, String> out = new LinkedHashMap<>();
        record.getValue().forEach((key, value) ->
                out.put(String.valueOf(key), value == null ? null : String.valueOf(value)));
        return out;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String lowerBound(final SessionRecord session, final String afterCursor) {
        if (afterCursor != null && !afterCursor.isBlank()) {
            return afterCursor.trim();
        }
        return Math.max(0L, session.sessionStartMs() - SESSION_START_LOOKBACK_MS) + "-0";
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultIfBlank(final String value, final String fallback) {
        final String trimmed = blankToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private static Long parseLong(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseInt(final String value) {
        final Long parsed = parseLong(value);
        if (parsed == null || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            return null;
        }
        return parsed.intValue();
    }

    private static Double parseDouble(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
