package com.example.auditretention.archive;

import com.example.auditretention.audit.AuditEventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — deterministic NDJSON.gz serializer
 * (ADR-0042 D4.5 / D4.7 / Codex C(e)).
 *
 * <p>Determinism is mandatory: {@code object_sha256} is computed over the EXACT
 * bytes produced here, and a re-run must not depend on re-serialization. Sources
 * of non-determinism are all pinned:
 * <ul>
 *   <li>row order = global {@code seq} ascending (caller passes them ordered);</li>
 *   <li>per-row JSON field order = alphabetical (canonical mapper);</li>
 *   <li>fixed Deflater level (BEST_COMPRESSION);</li>
 *   <li>{@link GZIPOutputStream} header carries MTIME=0 + OS=255 by JDK default
 *       (no filename, no timestamp) so the compressed bytes are stable;</li>
 *   <li>each record terminated by a single {@code '\n'} (trailing newline incl.).</li>
 * </ul>
 * The {@code NdjsonGzSerializerTest} asserts that serializing the same batch
 * twice yields byte-identical output.
 */
@Component
public class NdjsonGzSerializer {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Serialize rows (already in global seq order) to deterministic gzip bytes. */
    public byte[] serialize(List<AuditEventRecord> rows) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, rows.size() * 256));
        try (GZIPOutputStream gz = new GZIPOutputStream(baos) {
            {
                this.def.setLevel(Deflater.BEST_COMPRESSION);
            }
        }) {
            for (AuditEventRecord r : rows) {
                String line = CANONICAL_MAPPER.writeValueAsString(toNode(r));
                gz.write(line.getBytes(StandardCharsets.UTF_8));
                gz.write('\n');
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("NDJSON.gz serialization failed", ex);
        }
        return baos.toByteArray();
    }

    private ObjectNode toNode(AuditEventRecord r) {
        ObjectNode n = CANONICAL_MAPPER.createObjectNode();
        // Full column set (archive truth). Alphabetical order is applied by the
        // ORDER_MAP_ENTRIES_BY_KEYS mapper at write time, so insertion order here
        // is irrelevant to the bytes.
        putLong(n, "chunk_seq", r.getChunkSeq());
        n.put("correlation_id", r.getCorrelationId());
        n.put("dedup_key", r.getDedupKey());
        n.put("entry_hash", r.getEntryHash());
        n.put("entry_hash_alg", r.getEntryHashAlg());
        n.put("entry_hash_version", r.getEntryHashVersion());
        n.put("event_timestamp", r.getEventTimestamp() == null ? null : r.getEventTimestamp().toString());
        n.put("event_type", r.getEventType());
        n.put("http_status", r.getHttpStatus());
        n.put("id", r.getId() == null ? null : r.getId().toString().toLowerCase());
        n.put("ingested_at", r.getIngestedAt() == null ? null : r.getIngestedAt().toString());
        n.put("prev_hash", r.getPrevHash());
        n.put("rejection_code", r.getRejectionCode());
        putLong(n, "retry_after_seconds", r.getRetryAfterSeconds());
        putLong(n, "seq", r.getSeq());
        n.put("session_id", r.getSessionId());
        n.put("stream_entry_id", r.getStreamEntryId());
        putLong(n, "tenant_id", r.getTenantId());
        putLong(n, "user_id", r.getUserId());
        return n;
    }

    private static void putLong(ObjectNode n, String field, Long value) {
        if (value == null) {
            n.putNull(field);
        } else {
            n.put(field, value);
        }
    }
}
