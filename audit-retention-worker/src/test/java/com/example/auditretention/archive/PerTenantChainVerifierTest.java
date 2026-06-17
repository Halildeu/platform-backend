package com.example.auditretention.archive;

import com.example.auditretention.audit.AuditChainSupport;
import com.example.auditretention.audit.AuditEventRecord;
import com.example.auditretention.archive.PerTenantChainVerifier.Result;
import com.example.auditretention.archive.PerTenantChainVerifier.TenantAnchor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — PER_TENANT verify-before-archive
 * unit coverage (ADR-0042 D4.4): multi-tenant interleaving, GENESIS handling,
 * intra-segment linkage, tamper, and anchor (cross-batch) continuity — all
 * fail-closed.
 */
class PerTenantChainVerifierTest {

    private final PerTenantChainVerifier verifier = new PerTenantChainVerifier();

    @Test
    void interleavedMultiTenantSegmentVerifies() {
        // Global seq order: A1, B2, A3, B4, A5 — two independent tenant chains.
        AuditEventRecord a1 = genesis(7L, 1L);
        AuditEventRecord b2 = genesis(9L, 2L);
        AuditEventRecord a3 = linked(7L, 3L, a1);
        AuditEventRecord b4 = linked(9L, 4L, b2);
        AuditEventRecord a5 = linked(7L, 5L, a3);

        Result r = verifier.verify(List.of(a1, b2, a3, b4, a5), Map.of());

        assertThat(r.isValid()).isTrue();
        assertThat(r.snapshots()).hasSize(2);
        assertThat(r.snapshots()).anySatisfy(s -> {
            assertThat(s.tenantId()).isEqualTo(7L);
            assertThat(s.rowCount()).isEqualTo(3);
            assertThat(s.lastEntryHash()).isEqualTo(a5.getEntryHash());
        });
    }

    @Test
    void continuesAcrossBatchViaAnchor() {
        AuditEventRecord a1 = genesis(7L, 1L);
        // Second batch: A's chain continues from a1; anchor carries a1's hash.
        AuditEventRecord a2 = linked(7L, 2L, a1);
        Map<Long, TenantAnchor> anchors = Map.of(7L, new TenantAnchor(7L, a1.getEntryHash(), 1L));

        Result r = verifier.verify(List.of(a2), anchors);
        assertThat(r.isValid()).isTrue();
    }

    @Test
    void tamperedEntryHashIsDetected() {
        AuditEventRecord a1 = genesis(7L, 1L);
        a1.setEntryHash("deadbeef".repeat(8)); // 64 hex chars, but wrong
        Result r = verifier.verify(List.of(a1), Map.of());
        assertThat(r.isValid()).isFalse();
        assertThat(r.failureReason()).contains("tamper");
    }

    @Test
    void brokenIntraSegmentLinkageIsDetected() {
        AuditEventRecord a1 = genesis(7L, 1L);
        AuditEventRecord a3 = linked(7L, 3L, a1);
        a3.setPrevHash("0".repeat(64)); // wrong predecessor link
        // recompute a3's entry to keep it self-consistent but mis-linked
        a3.setEntryHash(AuditChainSupport.computeEntryHash(a3.getPrevHash(), a3));
        Result r = verifier.verify(List.of(a1, a3), Map.of());
        assertThat(r.isValid()).isFalse();
        assertThat(r.failureReason()).contains("linkage broken");
    }

    @Test
    void anchorDiscontinuityIsDetected() {
        AuditEventRecord a2 = linked(7L, 2L, genesis(7L, 1L));
        // Anchor claims a different last_entry_hash than a2.prev_hash.
        Map<Long, TenantAnchor> anchors = Map.of(7L, new TenantAnchor(7L, "f".repeat(64), 1L));
        Result r = verifier.verify(List.of(a2), anchors);
        assertThat(r.isValid()).isFalse();
        assertThat(r.failureReason()).contains("discontinuity");
    }

    @Test
    void firstSeenTenantNotGenesisIsRejected() {
        // No anchor, but the row claims a predecessor (prev_hash != null) — gap/tamper.
        AuditEventRecord a = linked(7L, 5L, genesis(7L, 1L));
        Result r = verifier.verify(List.of(a), Map.of());
        assertThat(r.isValid()).isFalse();
        assertThat(r.failureReason()).contains("GENESIS");
    }

    private static AuditEventRecord genesis(long tenantId, long seq) {
        return build(tenantId, seq, null);
    }

    private static AuditEventRecord linked(long tenantId, long seq, AuditEventRecord prev) {
        return build(tenantId, seq, prev.getEntryHash());
    }

    private static AuditEventRecord build(long tenantId, long seq, String prevHash) {
        AuditEventRecord e = new AuditEventRecord();
        e.setSeq(seq);
        e.setId(new UUID(tenantId, seq));
        e.setTenantId(tenantId);
        e.setEventType("CHUNK_ADMISSION_REJECTED");
        e.setSessionId("sess-" + tenantId + "-" + seq);
        e.setUserId(1000L + tenantId);
        e.setChunkSeq(seq);
        e.setHttpStatus(413);
        e.setRejectionCode("AUDIO_GATEWAY_OVERSIZE");
        e.setRetryAfterSeconds(null);
        e.setCorrelationId("corr-" + tenantId + "-" + seq);
        e.setEventTimestamp(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(seq));
        e.setDedupKey("dk-" + tenantId + "-" + seq);
        e.setEntryHashAlg(AuditChainSupport.HASH_ALGORITHM);
        e.setEntryHashVersion(AuditChainSupport.HASH_VERSION);
        e.setPrevHash(prevHash);
        e.setEntryHash(AuditChainSupport.computeEntryHash(prevHash, e));
        return e;
    }
}
