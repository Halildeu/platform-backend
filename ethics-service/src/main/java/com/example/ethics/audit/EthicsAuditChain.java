package com.example.ethics.audit;

import com.example.ethics.model.AuditOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/** Deterministic, versioned hash-chain format for Etik Speak audit entries. */
public final class EthicsAuditChain {
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final int HASH_VERSION = 1;
    public static final String DOMAIN_PREFIX = "faz35-ethics-worm-audit:v1";
    public static final String GENESIS = "GENESIS";

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final HexFormat HEX = HexFormat.of();

    private EthicsAuditChain() {
    }

    public static String canonicalPayload(AuditOutbox row) {
        ObjectNode root = CANONICAL_MAPPER.createObjectNode();
        root.put("aggregate_id", row.getAggregateId().toString().toLowerCase());
        root.put("event_timestamp", normalizeTimestamp(row.getCreatedAt()).toString());
        root.put("event_type", row.getEventType());
        root.put("org_id", row.getOrgId().toString().toLowerCase());
        // Preserve the exact already-redacted JSON bytes. Re-parsing here would
        // permit mapper/version changes to alter historical hash material.
        root.put("payload", row.getPayload());
        root.put("source_outbox_id", row.getId().toString().toLowerCase());
        try {
            return CANONICAL_MAPPER.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException("Ethics audit canonical payload serialization failed", error);
        }
    }

    public static String computeEntryHash(String previousHash, AuditOutbox row) {
        String previous = previousHash == null || previousHash.isBlank() ? GENESIS : previousHash;
        return sha256Hex(DOMAIN_PREFIX + "\nprev=" + previous + "\npayload=" + canonicalPayload(row));
    }

    public static Instant normalizeTimestamp(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    private static String sha256Hex(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
