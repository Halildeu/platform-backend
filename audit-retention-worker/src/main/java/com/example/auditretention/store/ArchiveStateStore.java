package com.example.auditretention.store;

import com.example.auditretention.archive.PerTenantChainVerifier.TenantAnchor;
import com.example.auditretention.archive.PerTenantChainVerifier.TenantAnchorSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — worker state store
 * (audit_archive_cursor / _ledger / _tenant_anchor). The {@link #commitSegment}
 * advance is the single atomic transaction (ADR-0042 D4.3/D4.7): it runs AFTER
 * S3 version/checksum/retention verification and updates ledger + per-tenant
 * anchors + cursor together, with the singleton cursor row {@code FOR UPDATE}
 * locked and an optimistic check against the expected pre-advance value.
 */
@Component
public class ArchiveStateStore {

    private final JdbcTemplate jdbc;

    public ArchiveStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long readCursor() {
        Long v = jdbc.queryForObject(
                "SELECT last_archived_seq FROM audit_archive.audit_archive_cursor WHERE id = 1", Long.class);
        return v == null ? 0L : v;
    }

    public Map<Long, TenantAnchor> loadTenantAnchors(Collection<Long> tenantIds) {
        Map<Long, TenantAnchor> out = new HashMap<>();
        if (tenantIds.isEmpty()) {
            return out;
        }
        // Small per-segment tenant cardinality — a simple per-id lookup is fine and
        // avoids dynamic IN-list SQL construction.
        for (Long tid : tenantIds) {
            jdbc.query(
                    "SELECT tenant_id, last_entry_hash, last_archived_seq "
                            + "FROM audit_archive.audit_archive_tenant_anchor WHERE tenant_id = ?",
                    rs -> {
                        out.put(rs.getLong("tenant_id"), new TenantAnchor(
                                rs.getLong("tenant_id"),
                                rs.getString("last_entry_hash"),
                                rs.getLong("last_archived_seq")));
                    }, tid);
        }
        return out;
    }

    public Optional<LedgerRecord> findLedger(String objectKey) {
        List<LedgerRecord> rows = jdbc.query(
                "SELECT object_key, object_version_id, manifest_key, manifest_version_id, object_sha256, "
                        + "manifest_sha256, retention_until, max_seq, verify_status "
                        + "FROM audit_archive.audit_archive_ledger WHERE object_key = ?",
                (rs, n) -> new LedgerRecord(
                        rs.getString("object_key"),
                        rs.getString("object_version_id"),
                        rs.getString("manifest_key"),
                        rs.getString("manifest_version_id"),
                        rs.getString("object_sha256"),
                        rs.getString("manifest_sha256"),
                        rs.getObject("retention_until", OffsetDateTime.class).toInstant(),
                        rs.getLong("max_seq"),
                        rs.getString("verify_status")),
                objectKey);
        return rows.stream().findFirst();
    }

    /**
     * Atomic segment commit: insert the ledger row, upsert per-tenant anchors,
     * advance the cursor — all in one transaction, guarded by a FOR UPDATE lock
     * on the singleton cursor row and an optimistic pre-advance check.
     */
    @Transactional
    public void commitSegment(long expectedCursorBefore, long cursorAfter,
                              LedgerInsert ledger, List<TenantAnchorSnapshot> anchors) {
        Long locked = jdbc.queryForObject(
                "SELECT last_archived_seq FROM audit_archive.audit_archive_cursor WHERE id = 1 FOR UPDATE",
                Long.class);
        long current = locked == null ? 0L : locked;
        if (current != expectedCursorBefore) {
            throw new IllegalStateException("cursor advanced concurrently: expected " + expectedCursorBefore
                    + " but found " + current + " (another worker?) — aborting segment");
        }

        jdbc.update(
                "INSERT INTO audit_archive.audit_archive_ledger ("
                        + "object_key, manifest_key, chain_scope, min_seq, max_seq, row_count, "
                        + "min_event_timestamp, max_event_timestamp, entry_hash_alg, entry_hash_version, "
                        + "object_sha256, object_version_id, manifest_sha256, manifest_version_id, "
                        + "retention_until, worker_image_digest, verify_status, tenant_anchors) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                ledger.objectKey(), ledger.manifestKey(), "PER_TENANT",
                ledger.minSeq(), ledger.maxSeq(), ledger.rowCount(),
                ts(ledger.minEventTimestamp()), ts(ledger.maxEventTimestamp()),
                ledger.entryHashAlg(), ledger.entryHashVersion(),
                ledger.objectSha256(), ledger.objectVersionId(),
                ledger.manifestSha256(), ledger.manifestVersionId(),
                ts(ledger.retentionUntil()),
                ledger.workerImageDigest() == null || ledger.workerImageDigest().isBlank()
                        ? null : ledger.workerImageDigest(),
                "VERIFIED", ledger.tenantAnchorsJson());

        for (TenantAnchorSnapshot a : anchors) {
            jdbc.update(
                    "INSERT INTO audit_archive.audit_archive_tenant_anchor "
                            + "(tenant_id, last_entry_hash, last_archived_seq, updated_at) "
                            + "VALUES (?,?,?, now()) "
                            + "ON CONFLICT (tenant_id) DO UPDATE SET "
                            + "last_entry_hash = EXCLUDED.last_entry_hash, "
                            + "last_archived_seq = EXCLUDED.last_archived_seq, updated_at = now() "
                            + "WHERE audit_archive_tenant_anchor.last_archived_seq < EXCLUDED.last_archived_seq",
                    a.tenantId(), a.lastEntryHash(), a.lastSeq());
        }

        int updated = jdbc.update(
                "UPDATE audit_archive.audit_archive_cursor SET last_archived_seq = ?, updated_at = now() "
                        + "WHERE id = 1 AND last_archived_seq = ?",
                cursorAfter, expectedCursorBefore);
        if (updated != 1) {
            throw new IllegalStateException("cursor advance failed (concurrent modification)");
        }
    }

    /**
     * Idempotent re-run catch-up: advance the cursor to {@code cursorAfter} only
     * if it is still at-or-below {@code expectedCursorBefore} (the ledger +
     * anchors for this segment already exist and were committed atomically, so
     * only the cursor can lag — e.g. after a forced reset). No-op if already past.
     */
    @Transactional
    public void advanceCursorOnly(long expectedCursorBefore, long cursorAfter) {
        jdbc.update(
                "UPDATE audit_archive.audit_archive_cursor SET last_archived_seq = ?, updated_at = now() "
                        + "WHERE id = 1 AND last_archived_seq = ? AND last_archived_seq < ?",
                cursorAfter, expectedCursorBefore, cursorAfter);
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    public record LedgerRecord(String objectKey, String objectVersionId, String manifestKey,
                               String manifestVersionId, String objectSha256, String manifestSha256,
                               Instant retentionUntil, long maxSeq, String verifyStatus) {
    }

    public record LedgerInsert(String objectKey, String manifestKey, long minSeq, long maxSeq, long rowCount,
                               Instant minEventTimestamp, Instant maxEventTimestamp, String entryHashAlg,
                               int entryHashVersion, String objectSha256, String objectVersionId,
                               String manifestSha256, String manifestVersionId, Instant retentionUntil,
                               String workerImageDigest, String tenantAnchorsJson) {
    }
}
