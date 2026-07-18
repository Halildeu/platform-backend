package com.example.transcript.service;

import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** Canonical/source advisory locks and one-way source aliases. */
@Component
public class SessionErasureFence {

    private final TranscriptSessionErasureTombstoneRepository tombstones;

    public SessionErasureFence(TranscriptSessionErasureTombstoneRepository tombstones) {
        this.tombstones = tombstones;
    }

    public void lock(String... keys) {
        Arrays.stream(keys)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .forEach(tombstones::acquireFence);
    }

    public void rejectErased(UUIDScope scope, String sourceSessionId) {
        if (tombstones.existsByTenantIdAndMeetingIdAndSessionId(
                scope.tenantId(), scope.meetingId(), scope.sessionId())) {
            throw new SessionErasedException();
        }
        String sourceHash = sourceHash(sourceSessionId);
        if (sourceHash != null && tombstones.existsByTenantIdAndMeetingIdAndSourceSessionHash(
                scope.tenantId(), scope.meetingId(), sourceHash)) {
            throw new SessionErasedException();
        }
    }

    public void rejectSourceErased(
            java.util.UUID tenantId, java.util.UUID meetingId, String sourceSessionId) {
        String sourceHash = sourceHash(sourceSessionId);
        if (sourceHash != null && tombstones.existsByTenantIdAndMeetingIdAndSourceSessionHash(
                tenantId, meetingId, sourceHash)) {
            throw new SessionErasedException();
        }
    }

    public static String canonicalKey(UUIDScope scope) {
        return "canonical|" + scope.tenantId() + "|" + scope.meetingId() + "|" + scope.sessionId();
    }

    public static String sourceKey(java.util.UUID tenantId, java.util.UUID meetingId, String sourceSessionId) {
        return sourceSessionId == null ? null
                : "source|" + tenantId + "|" + meetingId + "|" + sourceSessionId;
    }

    public static String sourceHash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record UUIDScope(
            java.util.UUID tenantId, java.util.UUID meetingId, java.util.UUID sessionId) {
    }

    public static class SessionErasedException extends IllegalStateException {
        public SessionErasedException() { super("SESSION_ERASED"); }
    }
}
