package com.example.auditconsumer.audit;

import com.example.auditconsumer.model.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — audit integrity hash-chain support.
 *
 * <p>Direct reuse of the BE-016 {@code endpoint-admin AuditChainSupport} pattern
 * (Codex 019e4f8e) applied to the {@link AuditEvent} field set:
 * {@code entry_hash = SHA-256( DOMAIN_PREFIX \n "prev=" <prev|GENESIS> \n
 * "payload=" <canonical-json> )}.
 *
 * <h2>Canonical payload — fixed field set</h2>
 * Covers exactly: {@code chunk_seq, correlation_id, dedup_key, entry_hash_alg,
 * entry_hash_version, event_timestamp, event_type, http_status, id, org_id,
 * rejection_code, retry_after_seconds, session_id, tenant_id, user_id}.
 * The chain columns ({@code entry_hash}, {@code prev_hash}) and the DB-assigned
 * {@code seq}/{@code ingested_at} are NEVER part of the canonical payload —
 * chain linkage is an outer composition, and DB-side timing must not affect the
 * content hash.
 *
 * <h2>Determinism</h2>
 * Alphabetic field order on the root object; {@link Instant} normalized to
 * microsecond precision (Postgres {@code timestamp(6)}) then ISO-8601 so a
 * re-read row hashes identically; {@link UUID} lowercase; {@code null} emitted
 * as JSON {@code null}.
 */
public final class AuditChainSupport {

    public static final String HASH_ALGORITHM = "SHA-256";
    public static final int HASH_VERSION = 1;
    public static final String DOMAIN_PREFIX = "faz24-audit-event:v1";
    public static final String GENESIS = "GENESIS";

    private static final HexFormat HEX = HexFormat.of();

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private AuditChainSupport() {
    }

    /** Build the canonical JSON payload string for an audit event. */
    public static String canonicalPayload(AuditEvent event) {
        ObjectNode root = CANONICAL_MAPPER.createObjectNode();
        // Alphabetic field order on the root for determinism.
        putLong(root, "chunk_seq", event.getChunkSeq());
        root.put("correlation_id", event.getCorrelationId());
        root.put("dedup_key", event.getDedupKey());
        root.put("entry_hash_alg", event.getEntryHashAlg());
        root.put("entry_hash_version", event.getEntryHashVersion());
        root.put("event_timestamp", event.getEventTimestamp() == null ? null
                : event.getEventTimestamp().toString());
        root.put("event_type", event.getEventType());
        root.put("http_status", event.getHttpStatus());
        root.put("id", uuidOrNull(event.getId()));
        root.put("org_id", uuidOrNull(event.getOrgId()));
        root.put("rejection_code", event.getRejectionCode());
        putLong(root, "retry_after_seconds", event.getRetryAfterSeconds());
        root.put("session_id", event.getSessionId());
        root.put("tenant_id", uuidOrNull(event.getTenantId()));
        putLong(root, "user_id", event.getUserId());
        try {
            return CANONICAL_MAPPER.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Audit canonical payload serialization failed", ex);
        }
    }

    /**
     * Compute the chained {@code entry_hash} given the previous row's hash
     * ({@code null}/blank = tenant GENESIS).
     */
    public static String computeEntryHash(String prevHash, AuditEvent event) {
        String prev = (prevHash == null || prevHash.isBlank()) ? GENESIS : prevHash;
        String composed = DOMAIN_PREFIX + "\n"
                + "prev=" + prev + "\n"
                + "payload=" + canonicalPayload(event);
        return sha256Hex(composed);
    }

    /** Normalize to microsecond precision (Postgres {@code timestamp(6)}). */
    public static Instant normalizeTimestamp(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static void putLong(ObjectNode root, String field, Long value) {
        if (value == null) {
            root.putNull(field);
        } else {
            root.put(field, value);
        }
    }

    private static String uuidOrNull(UUID uuid) {
        return uuid == null ? null : uuid.toString().toLowerCase();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
