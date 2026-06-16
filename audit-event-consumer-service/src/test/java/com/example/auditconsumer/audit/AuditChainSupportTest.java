package com.example.auditconsumer.audit;

import com.example.auditconsumer.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — unit tests for the audit hash-chain
 * canonicalizer + hasher. Pure JUnit, no Spring context (BE-016
 * {@code AuditChainSupportTest} pattern).
 */
class AuditChainSupportTest {

    private static final long TENANT = 4242L;

    private AuditEvent sampleEvent() {
        AuditEvent event = new AuditEvent();
        event.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        event.setTenantId(TENANT);
        event.setEventType("CHUNK_ADMISSION_REJECTED");
        event.setSessionId("sess-1");
        event.setUserId(7L);
        event.setChunkSeq(3L);
        event.setHttpStatus(429);
        event.setRejectionCode("QUEUE_FULL");
        event.setRetryAfterSeconds(10L);
        event.setCorrelationId("corr-1");
        event.setEventTimestamp(AuditChainSupport.normalizeTimestamp(
                Instant.parse("2026-06-16T09:52:15.123456Z")));
        event.setDedupKey("CHUNK_ADMISSION_REJECTED:sess-1:3");
        event.setEntryHashAlg(AuditChainSupport.HASH_ALGORITHM);
        event.setEntryHashVersion(AuditChainSupport.HASH_VERSION);
        return event;
    }

    @Test
    void canonicalPayloadIsDeterministic() {
        assertThat(AuditChainSupport.canonicalPayload(sampleEvent()))
                .isEqualTo(AuditChainSupport.canonicalPayload(sampleEvent()));
    }

    @Test
    void canonicalPayloadSerializesTenantAndUserAsNumbersNoOrgId() {
        // Producer contract: tenant_id/user_id are numeric companyId/userId, NOT
        // UUIDs; and there is no org_id column. The canonical JSON must reflect
        // that (numbers, unquoted) or a re-read row would not re-hash identically.
        String json = AuditChainSupport.canonicalPayload(sampleEvent());
        assertThat(json).contains("\"tenant_id\":4242");
        assertThat(json).contains("\"user_id\":7");
        assertThat(json).doesNotContain("org_id");
        assertThat(json).doesNotContain("\"tenant_id\":\"");
    }

    @Test
    void changedTenantChangesHash() {
        String baseline = AuditChainSupport.computeEntryHash(null, sampleEvent());
        AuditEvent mutated = sampleEvent();
        mutated.setTenantId(9999L);
        assertThat(AuditChainSupport.computeEntryHash(null, mutated)).isNotEqualTo(baseline);
    }

    @Test
    void computeEntryHashIsDeterministicAndLowercaseHex() {
        String hash1 = AuditChainSupport.computeEntryHash(null, sampleEvent());
        String hash2 = AuditChainSupport.computeEntryHash(null, sampleEvent());
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void changedFieldChangesHash() {
        String baseline = AuditChainSupport.computeEntryHash(null, sampleEvent());
        AuditEvent mutated = sampleEvent();
        mutated.setRejectionCode("TAMPERED");
        assertThat(AuditChainSupport.computeEntryHash(null, mutated)).isNotEqualTo(baseline);
    }

    @Test
    void changedHttpStatusChangesHash() {
        String baseline = AuditChainSupport.computeEntryHash(null, sampleEvent());
        AuditEvent mutated = sampleEvent();
        mutated.setHttpStatus(503);
        assertThat(AuditChainSupport.computeEntryHash(null, mutated)).isNotEqualTo(baseline);
    }

    @Test
    void differentPrevHashChangesEntryHash() {
        AuditEvent event = sampleEvent();
        String genesis = AuditChainSupport.computeEntryHash(null, event);
        String linked = AuditChainSupport.computeEntryHash(
                "abc123def456abc123def456abc123def456abc123def456abc123def456abcd", event);
        assertThat(linked).isNotEqualTo(genesis);
    }

    @Test
    void nullPrevAndGenesisSentinelProduceSameHash() {
        AuditEvent event = sampleEvent();
        assertThat(AuditChainSupport.computeEntryHash(null, event))
                .isEqualTo(AuditChainSupport.computeEntryHash("", event));
    }

    @Test
    void chainColumnsAreNotPartOfCanonicalPayload() {
        AuditEvent event = sampleEvent();
        String before = AuditChainSupport.canonicalPayload(event);
        event.setEntryHash("deadbeef".repeat(8));
        event.setPrevHash("cafebabe".repeat(8));
        assertThat(AuditChainSupport.canonicalPayload(event)).isEqualTo(before);
    }

    @Test
    void normalizeTimestampTruncatesToMicros() {
        Instant nanos = Instant.parse("2026-06-16T09:52:15.123456789Z");
        assertThat(AuditChainSupport.normalizeTimestamp(nanos))
                .isEqualTo(Instant.parse("2026-06-16T09:52:15.123456Z"));
    }

    @Test
    void changedHashAlgChangesHash() {
        String baseline = AuditChainSupport.computeEntryHash(null, sampleEvent());
        AuditEvent mutated = sampleEvent();
        mutated.setEntryHashAlg("SHA-1");
        assertThat(AuditChainSupport.computeEntryHash(null, mutated)).isNotEqualTo(baseline);
    }
}
