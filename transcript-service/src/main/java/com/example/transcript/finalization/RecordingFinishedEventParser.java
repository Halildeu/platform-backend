package com.example.transcript.finalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Fail-closed parser for the frozen meeting.event.v1 recording-finished wire. */
@Component
public class RecordingFinishedEventParser {

    static final String EVENT_TYPE = "meeting.recording.finished";
    static final String AGGREGATE_TYPE = "meeting.recording";
    static final int MAX_PAYLOAD_UTF8_BYTES = 8 * 1024;
    private static final Pattern SOURCE_SESSION =
            Pattern.compile("^SES-[A-Za-z0-9._:-]{1,124}$");
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "schema", "eventType", "analysisRunId", "meetingId", "tenantId",
            "orgId", "generatedAt", "recordingSessionId", "externalSessionId",
            "finishedAt");

    private final ObjectMapper mapper;

    public RecordingFinishedEventParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Returns null for a valid stream event type this consumer does not own. */
    public RecordingFinishedEvent parse(Map<String, String> fields) {
        if (!EVENT_TYPE.equals(fields.get("eventType"))) {
            return null;
        }
        try {
            String payload = required(fields, "payload");
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_UTF8_BYTES) {
                throw invalid("PAYLOAD_TOO_LARGE");
            }
            JsonNode root = mapper.readTree(payload);
            if (!root.isObject() || !fieldNames(root).equals(PAYLOAD_FIELDS)) {
                throw invalid("PAYLOAD_SHAPE");
            }
            requireText(root, "schema", "meeting.event.v1");
            requireText(root, "eventType", EVENT_TYPE);
            if (!root.path("analysisRunId").isNull()) {
                throw invalid("ANALYSIS_RUN_PRESENT");
            }

            UUID tenantId = uuid(root, "tenantId");
            UUID meetingId = uuid(root, "meetingId");
            UUID orgId = uuid(root, "orgId");
            UUID sessionId = uuid(root, "recordingSessionId");
            String externalSessionId = text(root, "externalSessionId");
            Instant finishedAt = instant(root, "finishedAt");
            instant(root, "generatedAt");
            if (!tenantId.equals(orgId)) {
                throw invalid("ORG_SCOPE");
            }
            if (!SOURCE_SESSION.matcher(externalSessionId).matches()) {
                throw invalid("SOURCE_SESSION_FORMAT");
            }

            requireOuter(fields, "aggregateType", AGGREGATE_TYPE);
            requireOuter(fields, "aggregateId", sessionId.toString());
            requireOuter(fields, "aggregateRevision", "1");
            requireOuter(fields, "meetingId", meetingId.toString());
            requireOuter(fields, "tenantId", tenantId.toString());
            requireOuter(fields, "orgId", orgId.toString());
            String expectedKey = AGGREGATE_TYPE + "|" + sessionId + "|" + EVENT_TYPE + "|1";
            requireOuter(fields, "eventKey", expectedKey);

            return new RecordingFinishedEvent(
                    expectedKey, sha256(payload), tenantId, meetingId, sessionId,
                    externalSessionId, finishedAt);
        } catch (RecordingFinishedEventInvalidException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid("MALFORMED");
        }
    }

    private Set<String> fieldNames(JsonNode root) {
        Set<String> names = new HashSet<>();
        root.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw invalid("MISSING_" + key.toUpperCase());
        }
        return value;
    }

    private void requireOuter(Map<String, String> fields, String key, String expected) {
        if (!expected.equals(required(fields, key))) {
            throw invalid("OUTER_" + key.toUpperCase());
        }
    }

    private void requireText(JsonNode root, String key, String expected) {
        if (!expected.equals(text(root, key))) {
            throw invalid("INNER_" + key.toUpperCase());
        }
    }

    private String text(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("INNER_" + key.toUpperCase());
        }
        return value.textValue();
    }

    private UUID uuid(JsonNode root, String key) {
        try {
            return UUID.fromString(text(root, key));
        } catch (IllegalArgumentException ex) {
            throw invalid("UUID_" + key.toUpperCase());
        }
    }

    private Instant instant(JsonNode root, String key) {
        try {
            return Instant.parse(text(root, key));
        } catch (DateTimeParseException ex) {
            throw invalid("INSTANT_" + key.toUpperCase());
        }
    }

    private String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private RecordingFinishedEventInvalidException invalid(String reason) {
        return new RecordingFinishedEventInvalidException(reason);
    }

    public static class RecordingFinishedEventInvalidException extends IllegalArgumentException {
        private final String reason;

        public RecordingFinishedEventInvalidException(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() { return reason; }
    }
}
