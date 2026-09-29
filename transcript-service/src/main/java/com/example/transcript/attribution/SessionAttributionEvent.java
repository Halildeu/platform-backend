package com.example.transcript.attribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parsed {@code directSttSessionAttribution.v1} event (#3746 BE-D4b).
 *
 * <p>Contract: platform-ai {@code docs/contracts/direct-stt-session-attribution.v1.schema.json}.
 * The producer (live-stt post-session batch) knows window TIME ranges but never window
 * text, so the event carries one dominant-or-UU assignment per window and the consumer
 * derives the UTF-16 turn from the stored window text it owns. Strict parse: unknown
 * top-level/window fields, a wrong schema/model const, an out-of-range value or a
 * duplicate windowSeq all reject the event (metadata-only error, DLQ'd by the consumer).
 */
public record SessionAttributionEvent(
        UUID tenantId,
        String sourceTenantId,
        UUID meetingId,
        String sourceSessionId,
        long transportEpoch,
        String model,
        String modelRevision,
        long audioSampleCount,
        List<WindowAssignment> windows) {

    public static final String SCHEMA = "directSttSessionAttribution.v1";
    public static final String MODEL = "pyannote/speaker-diarization-3.1";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SPEAKER =
            Pattern.compile("S[1-9][0-9]{0,2}|SPEAKER_[0-9]{2,3}|UU");
    private static final Set<String> TOP_FIELDS = Set.of(
            "schema", "tenantId", "meetingId", "sourceSessionId", "transportEpoch",
            "model", "modelRevision", "audioSampleCount", "generatedAt", "windows");
    private static final Set<String> WINDOW_FIELDS =
            Set.of("windowSeq", "speaker", "startMs", "endMs", "dominanceRatio");

    public record WindowAssignment(long windowSeq, String speaker, long startMs, long endMs) {
        public WindowAssignment {
            if (speaker == null || !SPEAKER.matcher(speaker).matches()
                    || windowSeq < 0 || startMs < 0 || endMs <= startMs) {
                throw invalid("window assignment out of contract");
            }
        }

        public boolean unknown() {
            return "UU".equals(speaker);
        }
    }

    public SessionAttributionEvent {
        if (tenantId == null || meetingId == null
                || sourceTenantId == null || sourceTenantId.isBlank()
                || sourceSessionId == null || sourceSessionId.isBlank()
                || sourceSessionId.length() > 128
                || transportEpoch < 0
                || !MODEL.equals(model)
                || modelRevision == null || modelRevision.isBlank()
                || modelRevision.length() > 128
                || audioSampleCount < 16_000L
                || windows == null || windows.isEmpty() || windows.size() > 4096) {
            throw invalid("event out of contract");
        }
        windows = List.copyOf(windows);
    }

    public static SessionAttributionEvent parse(String payloadJson) {
        if (payloadJson == null || payloadJson.length() > 1_048_576) {
            throw invalid("payload missing or oversized");
        }
        final JsonNode root;
        try {
            root = JSON.readTree(payloadJson);
        } catch (final Exception ex) {
            throw invalid("payload is not JSON");
        }
        if (root == null || !root.isObject()) {
            throw invalid("payload is not an object");
        }
        root.fieldNames().forEachRemaining(name -> {
            if (!TOP_FIELDS.contains(name)) {
                throw invalid("unknown field: " + name);
            }
        });
        if (!SCHEMA.equals(root.path("schema").textValue())) {
            throw invalid("schema mismatch");
        }
        final String tenant = requiredText(root, "tenantId", 64);
        final JsonNode windowsNode = root.path("windows");
        if (!windowsNode.isArray() || windowsNode.isEmpty()) {
            throw invalid("windows must be a non-empty array");
        }
        final List<WindowAssignment> windows = new ArrayList<>();
        final Set<Long> seen = new java.util.HashSet<>();
        for (final JsonNode item : windowsNode) {
            if (!item.isObject()) {
                throw invalid("window must be an object");
            }
            item.fieldNames().forEachRemaining(name -> {
                if (!WINDOW_FIELDS.contains(name)) {
                    throw invalid("unknown window field: " + name);
                }
            });
            for (final String field : List.of("windowSeq", "startMs", "endMs")) {
                if (!item.path(field).isIntegralNumber() || !item.path(field).canConvertToLong()) {
                    throw invalid(field + " must be an integer");
                }
            }
            final WindowAssignment assignment = new WindowAssignment(
                    item.path("windowSeq").longValue(),
                    item.path("speaker").textValue(),
                    item.path("startMs").longValue(),
                    item.path("endMs").longValue());
            if (!seen.add(assignment.windowSeq())) {
                throw invalid("duplicate windowSeq");
            }
            windows.add(assignment);
        }
        return new SessionAttributionEvent(
                tenantUuid(tenant),
                tenant,
                requiredUuid(root, "meetingId"),
                requiredText(root, "sourceSessionId", 128),
                requiredLong(root, "transportEpoch"),
                requiredText(root, "model", 128),
                requiredText(root, "modelRevision", 128),
                requiredLong(root, "audioSampleCount"),
                windows);
    }

    /** Same mapping the Direct-STT ingest applies to non-UUID tenants. */
    static UUID tenantUuid(String sourceTenantId) {
        try {
            return UUID.fromString(sourceTenantId.trim());
        } catch (final IllegalArgumentException ex) {
            return UUID.nameUUIDFromBytes(
                    ("company:" + sourceTenantId.trim()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String requiredText(JsonNode root, String field, int maxLength) {
        final String value = root.path(field).textValue();
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + " missing or oversized");
        }
        return value;
    }

    private static UUID requiredUuid(JsonNode root, String field) {
        try {
            return UUID.fromString(requiredText(root, field, 64));
        } catch (final IllegalArgumentException ex) {
            throw invalid(field + " must be a UUID");
        }
    }

    private static long requiredLong(JsonNode root, String field) {
        final JsonNode node = root.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid(field + " must be an integer");
        }
        return node.longValue();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("session attribution event: " + reason);
    }
}
