package com.example.auditconsumer.events;

import com.example.auditconsumer.model.ConsentEventOutbox;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public record ConsentEventMessage(
        String eventKey,
        String eventType,
        UUID aggregateId,
        UUID meetingId,
        UUID tenantId,
        UUID orgId,
        String payloadJson) {

    public static ConsentEventMessage from(ConsentEventOutbox row) {
        if (!"meeting.consent.revoked".equals(row.getEventType())) {
            throw new IllegalStateException("Unsupported consent outbox event type");
        }
        byte[] actualHash = sha256(row.getPayload());
        byte[] expectedHash;
        try {
            expectedHash = HexFormat.of().parseHex(row.getPayloadHash());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid consent outbox payload hash", ex);
        }
        if (!MessageDigest.isEqual(expectedHash, actualHash)) {
            throw new IllegalStateException("Consent outbox payload hash mismatch");
        }
        return new ConsentEventMessage(
                row.getEventKey(),
                row.getEventType(),
                row.getAggregateId(),
                row.getMeetingId(),
                row.getTenantId(),
                row.getOrgId(),
                row.getPayload());
    }

    static String payloadHash(String payload) {
        return HexFormat.of().formatHex(sha256(payload));
    }

    private static byte[] sha256(String payload) {
        if (payload == null) {
            throw new IllegalStateException("Consent outbox payload is missing");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
