package com.example.auditretention.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — canonicalization parity backstop
 * (ADR-0042 C(a), Codex golden-vector guardrail).
 *
 * <p>The golden vectors are REAL rows written by the live
 * {@code audit-event-consumer-service} (provenance {@code platform-backend@74c9e1a9}).
 * Their {@code entry_hash} values are the producer's own output, so this is a
 * non-circular cross-impl parity check: the worker's verbatim-copied
 * {@link AuditChainSupport} must reproduce each stored {@code entry_hash}
 * exactly — covering tenant GENESIS ({@code prev_hash == null}), non-genesis
 * linkage, a nullable field ({@code retry_after_seconds == null}), field
 * ordering, and production timestamp round-trip. If the copy ever drifts a
 * single byte from the consumer's canonicalization, this fails the build.
 */
class AuditChainGoldenVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void copiedCanonicalizationReproducesLiveConsumerEntryHashes() throws Exception {
        JsonNode root = load();
        assertThat(root.get("domain_prefix").asText()).isEqualTo(AuditChainSupport.DOMAIN_PREFIX);
        JsonNode vectors = root.get("vectors");
        assertThat(vectors).isNotEmpty();

        for (JsonNode v : vectors) {
            AuditEventRecord e = toRecord(v);
            String expected = v.get("entry_hash").asText();
            String prevHash = v.hasNonNull("prev_hash") ? v.get("prev_hash").asText() : null;

            String recomputed = AuditChainSupport.computeEntryHash(prevHash, e);
            assertThat(recomputed)
                    .as("entry_hash parity for seq=%s (event %s)", v.get("seq"), e.getId())
                    .isEqualTo(expected);
        }
    }

    @Test
    void genesisRowHasNullPrevHash() throws Exception {
        JsonNode v = load().get("vectors").get(0);
        assertThat(v.hasNonNull("prev_hash")).isFalse();
        AuditEventRecord e = toRecord(v);
        assertThat(AuditChainSupport.computeEntryHash(null, e)).isEqualTo(v.get("entry_hash").asText());
    }

    @Test
    void linkedRowsChainToPredecessorEntryHash() throws Exception {
        JsonNode vectors = load().get("vectors");
        for (int i = 1; i < vectors.size(); i++) {
            String prevEntry = vectors.get(i - 1).get("entry_hash").asText();
            String thisPrev = vectors.get(i).get("prev_hash").asText();
            assertThat(thisPrev)
                    .as("row %d prev_hash links to row %d entry_hash", i, i - 1)
                    .isEqualTo(prevEntry);
        }
    }

    @Test
    void oneByteCanonicalizationDriftFailsVerification() throws Exception {
        JsonNode v = load().get("vectors").get(0);
        AuditEventRecord e = toRecord(v);
        String stored = v.get("entry_hash").asText();

        // Flip a single character in one canonical field — the hash MUST change,
        // proving the chain is sensitive to any canonicalization drift.
        e.setSessionId(e.getSessionId() + "X");
        String drifted = AuditChainSupport.computeEntryHash(null, e);
        assertThat(drifted).isNotEqualTo(stored);
    }

    private static AuditEventRecord toRecord(JsonNode v) {
        AuditEventRecord e = new AuditEventRecord();
        e.setSeq(v.get("seq").asLong());
        e.setId(UUID.fromString(v.get("id").asText()));
        e.setTenantId(v.get("tenant_id").asLong());
        e.setEventType(text(v, "event_type"));
        e.setSessionId(text(v, "session_id"));
        e.setUserId(longOrNull(v, "user_id"));
        e.setChunkSeq(longOrNull(v, "chunk_seq"));
        e.setHttpStatus(intOrNull(v, "http_status"));
        e.setRejectionCode(text(v, "rejection_code"));
        e.setRetryAfterSeconds(longOrNull(v, "retry_after_seconds"));
        e.setCorrelationId(text(v, "correlation_id"));
        long micros = v.get("event_timestamp_epoch_micros").asLong();
        e.setEventTimestamp(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1_000L));
        e.setDedupKey(text(v, "dedup_key"));
        e.setEntryHashAlg(text(v, "entry_hash_alg"));
        e.setEntryHashVersion(intOrNull(v, "entry_hash_version"));
        e.setPrevHash(text(v, "prev_hash"));
        e.setEntryHash(text(v, "entry_hash"));
        return e;
    }

    private static String text(JsonNode v, String f) {
        return v.hasNonNull(f) ? v.get(f).asText() : null;
    }

    private static Long longOrNull(JsonNode v, String f) {
        return v.hasNonNull(f) ? v.get(f).asLong() : null;
    }

    private static Integer intOrNull(JsonNode v, String f) {
        return v.hasNonNull(f) ? v.get(f).asInt() : null;
    }

    private static JsonNode load() throws Exception {
        try (InputStream in = AuditChainGoldenVectorTest.class.getResourceAsStream(
                "/audit-chain-golden-vectors.json")) {
            return MAPPER.readTree(in);
        }
    }
}
