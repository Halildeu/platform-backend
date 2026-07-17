package com.example.transcript.directstt;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parsed audio-gateway direct-STT result stream event.
 *
 * <p>Validation errors are metadata-only by design; exception messages never
 * include transcript text from {@code textDraft}.
 */
public record DirectSttTranscriptResultEvent(
        String entryId,
        UUID tenantId,
        String sourceTenantId,
        String sourceUserId,
        UUID meetingId,
        String sourceSessionId,
        long chunkSeq,
        long chunkStartedAtMs,
        String correlationId,
        String sha256,
        String textDraft,
        Double durationSeconds
) {

    public static final String SCHEMA_VERSION = "audioGateway.directSttTranscriptResult.v1";
    public static final String EVENT_TYPE = "DIRECT_STT_TRANSCRIPT_RESULT";
    public static final String SOURCE_SYSTEM = "DIRECT_STT";

    private static final int MAX_SOURCE_ID_LEN = 128;
    private static final int MAX_CORRELATION_ID_LEN = 128;
    private static final int MAX_SHA256_LEN = 128;
    private static final Pattern DIRECT_STT_SESSION_ID =
            Pattern.compile("^SES-[A-Za-z0-9._:-]{1,124}$");

    public static DirectSttTranscriptResultEvent parse(Map<String, String> fields, String entryId) {
        requireEquals(fields, "schemaVersion", SCHEMA_VERSION);
        requireEquals(fields, "eventType", EVENT_TYPE);
        String sourceTenantId = required(fields, "tenantId", MAX_SOURCE_ID_LEN);
        String sourceSessionId = required(fields, "sessionId", MAX_SOURCE_ID_LEN);
        if (!DIRECT_STT_SESSION_ID.matcher(sourceSessionId).matches()) {
            throw invalid("sessionId must be a valid SES-* id");
        }
        UUID tenantId = canonicalTenantId(sourceTenantId);
        UUID meetingId = requiredUuid(fields, "meetingId");
        long chunkSeq = requiredLong(fields, "chunkSeq");
        if (chunkSeq < 0) {
            throw invalid("chunkSeq must be >= 0");
        }
        long chunkStartedAtMs = requiredLong(fields, "chunkStartedAtMs");
        if (chunkStartedAtMs < 0) {
            throw invalid("chunkStartedAtMs must be >= 0");
        }
        String textDraft = required(fields, "textDraft", Integer.MAX_VALUE);
        if (textDraft.isBlank()) {
            throw invalid("textDraft must be nonblank");
        }
        Double durationSeconds = optionalDouble(fields, "durationSeconds");
        if (durationSeconds != null && durationSeconds < 0.0d) {
            throw invalid("durationSeconds must be >= 0");
        }
        String status = optional(fields, "status", 32);
        if (status != null && !status.isBlank() && !"DRAFT".equals(status)) {
            throw invalid("status must be DRAFT");
        }
        return new DirectSttTranscriptResultEvent(
                blankToNull(entryId),
                tenantId,
                sourceTenantId,
                optional(fields, "userId", MAX_SOURCE_ID_LEN),
                meetingId,
                sourceSessionId,
                chunkSeq,
                chunkStartedAtMs,
                optional(fields, "correlationId", MAX_CORRELATION_ID_LEN),
                optional(fields, "sha256", MAX_SHA256_LEN),
                textDraft,
                durationSeconds);
    }

    private static void requireEquals(Map<String, String> fields, String key, String expected) {
        String value = required(fields, key, 128);
        if (!expected.equals(value)) {
            throw invalid(key + " is not supported");
        }
    }

    private static UUID canonicalTenantId(String sourceTenantId) {
        UUID direct = parseUuid(sourceTenantId);
        if (direct != null) {
            return direct;
        }
        return UUID.nameUUIDFromBytes(("company:" + sourceTenantId.trim()).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID requiredUuid(Map<String, String> fields, String key) {
        UUID parsed = parseUuid(required(fields, key, 64));
        if (parsed == null) {
            throw invalid(key + " must be a UUID");
        }
        return parsed;
    }

    private static Long requiredLong(Map<String, String> fields, String key) {
        String value = required(fields, key, 64);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw invalid(key + " must be a long");
        }
    }

    private static Double optionalDouble(Map<String, String> fields, String key) {
        String value = optional(fields, key, 64);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw invalid(key + " must be a double");
        }
    }

    private static String required(Map<String, String> fields, String key, int maxLength) {
        String value = optional(fields, key, maxLength);
        if (value == null || value.isBlank()) {
            throw invalid(key + " is required");
        }
        return value;
    }

    private static String optional(Map<String, String> fields, String key, int maxLength) {
        String value = blankToNull(fields.get(key));
        if (value != null && value.length() > maxLength) {
            throw invalid(key + " is too long");
        }
        return value;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static InvalidDirectSttTranscriptResultException invalid(String reason) {
        return new InvalidDirectSttTranscriptResultException(reason);
    }

    public static class InvalidDirectSttTranscriptResultException extends IllegalArgumentException {
        public InvalidDirectSttTranscriptResultException(String message) {
            super(message);
        }
    }
}
