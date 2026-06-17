package com.example.auditconsumer.audit;

import com.example.auditconsumer.model.AuditEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — golden-vector PARITY guard.
 *
 * <p>The SAME {@code audit-chain-golden-vectors.json} fixture is asserted by BOTH
 * this consumer module AND the {@code audit-retention-worker} module (which holds
 * a verbatim copy of {@link AuditChainSupport}). The fixture's {@code entry_hash}
 * values are real producer output (these live rows were written by this very
 * service). Pinning both modules to one fixture means any future change to either
 * copy of the canonicalization fails its module's build — the retention worker can
 * never silently diverge from the producer's hash, which would (falsely) flag every
 * archived chain as tampered. Keep this fixture byte-identical to
 * {@code audit-retention-worker/src/test/resources/audit-chain-golden-vectors.json}.
 */
class AuditChainGoldenVectorParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void producerCanonicalizationReproducesFixtureEntryHashes() throws Exception {
        JsonNode root = load();
        assertThat(root.get("domain_prefix").asText()).isEqualTo(AuditChainSupport.DOMAIN_PREFIX);
        JsonNode vectors = root.get("vectors");
        assertThat(vectors).isNotEmpty();

        for (JsonNode v : vectors) {
            AuditEvent e = toEvent(v);
            String prevHash = v.hasNonNull("prev_hash") ? v.get("prev_hash").asText() : null;
            String expected = v.get("entry_hash").asText();
            assertThat(AuditChainSupport.computeEntryHash(prevHash, e))
                    .as("entry_hash parity for seq=%s", v.get("seq"))
                    .isEqualTo(expected);
        }
    }

    private static AuditEvent toEvent(JsonNode v) {
        AuditEvent e = new AuditEvent();
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
        try (InputStream in = AuditChainGoldenVectorParityTest.class.getResourceAsStream(
                "/audit-chain-golden-vectors.json")) {
            return MAPPER.readTree(in);
        }
    }
}
