package com.example.auditretention.archive;

import com.example.auditretention.archive.PerTenantChainVerifier.TenantAnchorSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — segment manifest builder
 * (ADR-0042 D4.6).
 *
 * <p>The manifest body deliberately does NOT carry {@code manifest_sha256}
 * (self-reference). The digest is computed over the canonical manifest bytes
 * (sorted keys, UTF-8, no insignificant whitespace) and persisted to the ledger
 * (+ optional {@code <key>.sha256} sidecar) — never written back into the body
 * where it would invalidate its own hash.
 */
@Component
public class ManifestBuilder {

    public static final int SCHEMA_VERSION = 1;
    public static final String ARTIFACT_KIND = "audit-archive-segment";

    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public record BuiltManifest(byte[] bytes, String sha256) {
    }

    public BuiltManifest build(SegmentMeta meta, List<TenantAnchorSnapshot> anchors) {
        ObjectNode m = CANONICAL_MAPPER.createObjectNode();
        m.put("artifact_kind", ARTIFACT_KIND);
        m.put("chain_scope", "PER_TENANT");
        m.put("created_at", meta.createdAt().toString());
        m.put("entry_hash_alg", meta.entryHashAlg());
        m.put("entry_hash_version", meta.entryHashVersion());
        m.put("max_event_timestamp", meta.maxEventTimestamp().toString());
        m.put("max_seq", meta.maxSeq());
        m.put("min_event_timestamp", meta.minEventTimestamp().toString());
        m.put("min_seq", meta.minSeq());
        m.put("object_key", meta.objectKey());
        m.put("object_sha256", meta.objectSha256());
        m.put("retention_until", meta.retentionUntil().toString());
        m.put("row_count", meta.rowCount());
        m.put("schema_version", SCHEMA_VERSION);
        // source_watermark: cursor before -> after (segment advance).
        ObjectNode wm = m.putObject("source_watermark");
        wm.put("cursor_after", meta.cursorAfter());
        wm.put("cursor_before", meta.cursorBefore());
        m.put("worker_image_digest", meta.workerImageDigest() == null || meta.workerImageDigest().isBlank()
                ? null : meta.workerImageDigest());

        // tenant_anchors[] sorted by tenant_id for deterministic bytes.
        ArrayNode arr = m.putArray("tenant_anchors");
        anchors.stream()
                .sorted(Comparator.comparingLong(TenantAnchorSnapshot::tenantId))
                .forEach(a -> {
                    ObjectNode an = arr.addObject();
                    an.put("first_prev_hash", a.firstPrevHash());
                    an.put("first_seq", a.firstSeq());
                    an.put("last_entry_hash", a.lastEntryHash());
                    an.put("last_seq", a.lastSeq());
                    an.put("row_count", a.rowCount());
                    an.put("tenant_id", a.tenantId());
                });

        byte[] bytes;
        try {
            bytes = CANONICAL_MAPPER.writeValueAsBytes(m);
        } catch (Exception ex) {
            throw new IllegalStateException("manifest serialization failed", ex);
        }
        return new BuiltManifest(bytes, sha256Hex(bytes));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Immutable manifest inputs. */
    public record SegmentMeta(
            String objectKey,
            long minSeq,
            long maxSeq,
            long rowCount,
            Instant minEventTimestamp,
            Instant maxEventTimestamp,
            String entryHashAlg,
            int entryHashVersion,
            String objectSha256,
            Instant retentionUntil,
            long cursorBefore,
            long cursorAfter,
            String workerImageDigest,
            Instant createdAt) {
    }
}
