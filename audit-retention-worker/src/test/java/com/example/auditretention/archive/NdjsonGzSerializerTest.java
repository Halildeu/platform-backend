package com.example.auditretention.archive;

import com.example.auditretention.audit.AuditEventRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — NDJSON.gz determinism
 * (ADR-0042 D4.7 / Codex C(e)): {@code object_sha256} is computed over these
 * exact bytes, so serializing the same batch twice must be byte-identical.
 */
class NdjsonGzSerializerTest {

    private final NdjsonGzSerializer serializer = new NdjsonGzSerializer();

    @Test
    void sameBatchSerializesToByteIdenticalGzip() {
        List<AuditEventRecord> batch = List.of(record(1L, 7L, null), record(2L, 7L, "deadbeef"));
        byte[] a = serializer.serialize(batch);
        byte[] b = serializer.serialize(batch);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void gzipRoundTripsToNdjsonWithFullColumns() throws Exception {
        AuditEventRecord r = record(5L, 99L, null);
        byte[] gz = serializer.serialize(List.of(r));
        String ndjson = gunzip(gz);
        assertThat(ndjson).endsWith("\n");
        String line = ndjson.strip();
        // canonical alphabetical key order; full column set; null prev_hash emitted.
        assertThat(line).contains("\"seq\":5");
        assertThat(line).contains("\"tenant_id\":99");
        assertThat(line).contains("\"prev_hash\":null");
        assertThat(line).contains("\"entry_hash\":\"hash-5\"");
        // alphabetical ordering: chunk_seq precedes correlation_id precedes dedup_key
        assertThat(line.indexOf("\"chunk_seq\"")).isLessThan(line.indexOf("\"correlation_id\""));
        assertThat(line.indexOf("\"correlation_id\"")).isLessThan(line.indexOf("\"dedup_key\""));
    }

    @Test
    void differentContentProducesDifferentBytes() {
        byte[] a = serializer.serialize(List.of(record(1L, 7L, null)));
        byte[] b = serializer.serialize(List.of(record(1L, 7L, "x")));
        assertThat(a).isNotEqualTo(b);
    }

    private static AuditEventRecord record(long seq, long tenantId, String prevHash) {
        AuditEventRecord r = new AuditEventRecord();
        r.setSeq(seq);
        r.setId(UUID.fromString("00000000-0000-0000-0000-0000000000" + String.format("%02d", seq)));
        r.setTenantId(tenantId);
        r.setEventType("CHUNK_ADMISSION_REJECTED");
        r.setSessionId("s-" + seq);
        r.setUserId(1000L);
        r.setChunkSeq(0L);
        r.setHttpStatus(413);
        r.setRejectionCode("AUDIO_GATEWAY_OVERSIZE");
        r.setRetryAfterSeconds(null);
        r.setCorrelationId("corr-" + seq);
        r.setEventTimestamp(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seq));
        r.setIngestedAt(Instant.parse("2026-01-01T00:00:01Z").plusSeconds(seq));
        r.setDedupKey("dk-" + seq);
        r.setStreamEntryId("1700000000000-" + seq);
        r.setPrevHash(prevHash);
        r.setEntryHash("hash-" + seq);
        r.setEntryHashAlg("SHA-256");
        r.setEntryHashVersion(1);
        return r;
    }

    private static String gunzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
