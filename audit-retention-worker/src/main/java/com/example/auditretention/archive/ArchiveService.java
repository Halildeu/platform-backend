package com.example.auditretention.archive;

import com.example.auditretention.audit.AuditChainSupport;
import com.example.auditretention.audit.AuditEventRecord;
import com.example.auditretention.archive.ManifestBuilder.BuiltManifest;
import com.example.auditretention.archive.ManifestBuilder.SegmentMeta;
import com.example.auditretention.archive.PerTenantChainVerifier.TenantAnchor;
import com.example.auditretention.archive.PerTenantChainVerifier.TenantAnchorSnapshot;
import com.example.auditretention.archive.S3ArchiveClient.ObjectHead;
import com.example.auditretention.config.AuditRetentionProperties;
import com.example.auditretention.metrics.ArchiveMetrics;
import com.example.auditretention.store.ArchiveStateStore;
import com.example.auditretention.store.ArchiveStateStore.LedgerInsert;
import com.example.auditretention.store.ArchiveStateStore.LedgerRecord;
import com.example.auditretention.store.AuditEventReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — archival orchestrator (ADR-0042
 * D4). One {@link #runOnce()} drains every currently-eligible contiguous-prefix
 * segment: PER_TENANT verify-before-archive (fail-closed) → deterministic
 * NDJSON.gz + manifest → S3 PutObject under COMPLIANCE with version-id capture
 * + version-specific checksum/retention/latest verification → single atomic DB
 * advance (ledger + per-tenant anchors + cursor). Never deletes a source row.
 */
@Service
public class ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);
    private static final long RETENTION_TOLERANCE_SECONDS = 5L;

    private final AuditRetentionProperties props;
    private final AuditEventReader reader;
    private final ArchiveStateStore stateStore;
    private final PerTenantChainVerifier verifier;
    private final NdjsonGzSerializer ndjson;
    private final ManifestBuilder manifestBuilder;
    private final S3ArchiveClient s3;
    private final ArchiveMetrics metrics;
    private final ObjectMapper anchorMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public ArchiveService(AuditRetentionProperties props, AuditEventReader reader, ArchiveStateStore stateStore,
                          PerTenantChainVerifier verifier, NdjsonGzSerializer ndjson, ManifestBuilder manifestBuilder,
                          S3ArchiveClient s3, ArchiveMetrics metrics) {
        this.props = props;
        this.reader = reader;
        this.stateStore = stateStore;
        this.verifier = verifier;
        this.ndjson = ndjson;
        this.manifestBuilder = manifestBuilder;
        this.s3 = s3;
        this.metrics = metrics;
    }

    public record ArchiveRunResult(long rowsArchived, int segmentsWritten, long lagSeconds) {
    }

    /** Drain all currently-eligible contiguous-prefix segments. */
    public ArchiveRunResult runOnce() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(props.getHotWindowDays(), ChronoUnit.DAYS);
        long totalRows = 0;
        int segments = 0;
        try {
            while (true) {
                long cursor = stateStore.readCursor();
                List<AuditEventRecord> prefix =
                        reader.scanContiguousEligiblePrefix(cursor, cutoff, props.getScanBatchSize());
                if (prefix.isEmpty()) {
                    break;
                }
                List<AuditEventRecord> segment = prefix.size() > props.getMaxSegmentRows()
                        ? List.copyOf(prefix.subList(0, props.getMaxSegmentRows()))
                        : prefix;
                long archived = archiveSegment(segment, cursor, now);
                if (archived > 0) {
                    totalRows += archived;
                    segments++;
                }
            }
        } catch (RuntimeException ex) {
            metrics.error();
            throw ex;
        }
        long lag = reader.lagSeconds(stateStore.readCursor(), cutoff, now);
        metrics.setLagSeconds(lag);
        metrics.setLastRunRowsArchived(totalRows);
        log.info("audit-retention run complete: rows={} segments={} lagSeconds={}", totalRows, segments, lag);
        return new ArchiveRunResult(totalRows, segments, lag);
    }

    /** Archive one segment. Returns rows archived (0 = idempotent skip). */
    private long archiveSegment(List<AuditEventRecord> segment, long cursorBefore, Instant now) {
        long minSeq = segment.get(0).getSeq();
        long maxSeq = segment.get(segment.size() - 1).getSeq();
        String objectKey = deriveObjectKey(minSeq, maxSeq);
        String manifestKey = objectKey + ".manifest.json";

        // 1. Idempotent re-run: a VERIFIED ledger row for this key already exists.
        Optional<LedgerRecord> existing = stateStore.findLedger(objectKey);
        if (existing.isPresent()) {
            verifyRecordedVersions(existing.get(), objectKey, manifestKey);
            stateStore.advanceCursorOnly(cursorBefore, maxSeq); // catch cursor up if it lagged
            metrics.idempotentSkip();
            log.info("segment {} already archived (version-id verified) — skip put", objectKey);
            return 0;
        }

        // 2. Ledger-absent + object already present (HEAD 200) => fail-closed anomaly
        //    (crash mid-write or external interference; ADR-0042 D4.7).
        if (s3.objectExists(objectKey)) {
            metrics.anomaly();
            throw new ArchiveAnomalyException("ledger-absent but object exists at " + objectKey
                    + " — fail-closed (crash mid-write or external); manual adoption required");
        }

        // 3. PER_TENANT verify-before-archive (fail-closed).
        Set<Long> tenantIds = new LinkedHashSet<>();
        segment.forEach(r -> tenantIds.add(r.getTenantId()));
        var anchors = stateStore.loadTenantAnchors(tenantIds);
        PerTenantChainVerifier.Result vr = verifier.verify(segment, anchors);
        if (!vr.isValid()) {
            metrics.chainBreak();
            throw new ChainBreakException("verify-before-archive failed for segment " + objectKey
                    + ": " + vr.failureReason());
        }

        // 4. Deterministic NDJSON.gz + manifest.
        byte[] objectBytes = ndjson.serialize(segment);
        String objectSha = ManifestBuilder.sha256Hex(objectBytes);
        Instant retentionUntil = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
                .plusYears(props.getRetentionYears()).toInstant();
        AuditEventRecord head = segment.get(0);
        SegmentMeta meta = new SegmentMeta(objectKey, minSeq, maxSeq, segment.size(),
                minEventTimestamp(segment), maxEventTimestamp(segment),
                orDefault(head.getEntryHashAlg(), AuditChainSupport.HASH_ALGORITHM),
                head.getEntryHashVersion() == null ? AuditChainSupport.HASH_VERSION : head.getEntryHashVersion(),
                objectSha, retentionUntil, cursorBefore, maxSeq, props.getWorkerImageDigest(), now);
        BuiltManifest manifest = manifestBuilder.build(meta, vr.snapshots());

        // 5. S3 writes (version-id + checksum + COMPLIANCE retention) — BEFORE the
        //    DB commit; each verified version-specifically.
        String objectVersionId = s3.putObject(objectKey, objectBytes, objectSha, retentionUntil);
        verifyJustWritten(objectKey, objectVersionId, objectSha, retentionUntil);
        String manifestVersionId = s3.putObject(manifestKey, manifest.bytes(), manifest.sha256(), retentionUntil);
        verifyJustWritten(manifestKey, manifestVersionId, manifest.sha256(), retentionUntil);

        // 6. Single atomic DB advance: ledger + per-tenant anchors + cursor.
        LedgerInsert li = new LedgerInsert(objectKey, manifestKey, minSeq, maxSeq, segment.size(),
                minEventTimestamp(segment), maxEventTimestamp(segment), meta.entryHashAlg(), meta.entryHashVersion(),
                objectSha, objectVersionId, manifest.sha256(), manifestVersionId, retentionUntil,
                props.getWorkerImageDigest(), tenantAnchorsJson(vr.snapshots()));
        stateStore.commitSegment(cursorBefore, maxSeq, li, vr.snapshots());

        metrics.segmentWritten();
        metrics.rowsArchived(segment.size());
        log.info("archived segment {} rows={} tenants={} objectVersion={} retentionUntil={}",
                objectKey, segment.size(), vr.snapshots().size(), objectVersionId, retentionUntil);
        return segment.size();
    }

    /** Verify the version we just PUT: version-specific checksum + COMPLIANCE retention + latest==written. */
    private void verifyJustWritten(String key, String versionId, String expectedSha, Instant expectedRetention) {
        ObjectHead v = s3.headVersion(key, versionId)
                .orElseThrow(() -> anomaly("just-written version missing for " + key + " v=" + versionId));
        checkContent(key, v, expectedSha, expectedRetention, "post-put");
        assertLatestIs(key, versionId, "post-put");
    }

    /** Idempotent re-run: verify the ledger-recorded versions (object + manifest). */
    private void verifyRecordedVersions(LedgerRecord rec, String objectKey, String manifestKey) {
        ObjectHead obj = s3.headVersion(objectKey, rec.objectVersionId())
                .orElseThrow(() -> anomaly("recorded object version missing: " + objectKey + " v=" + rec.objectVersionId()));
        checkContent(objectKey, obj, rec.objectSha256(), rec.retentionUntil(), "re-run object");
        assertLatestIs(objectKey, rec.objectVersionId(), "re-run object");

        ObjectHead man = s3.headVersion(manifestKey, rec.manifestVersionId())
                .orElseThrow(() -> anomaly("recorded manifest version missing: " + manifestKey + " v=" + rec.manifestVersionId()));
        checkContent(manifestKey, man, rec.manifestSha256(), rec.retentionUntil(), "re-run manifest");
        assertLatestIs(manifestKey, rec.manifestVersionId(), "re-run manifest");
    }

    private void checkContent(String key, ObjectHead head, String expectedShaHex, Instant expectedRetention, String phase) {
        if (head.checksumSha256Hex() == null || !head.checksumSha256Hex().equalsIgnoreCase(expectedShaHex)) {
            throw anomaly(phase + " checksum mismatch for " + key + ": expected " + expectedShaHex
                    + " got " + head.checksumSha256Hex());
        }
        if (head.objectLockMode() != ObjectLockMode.COMPLIANCE) {
            throw anomaly(phase + " object-lock mode not COMPLIANCE for " + key + ": " + head.objectLockMode());
        }
        // Re-run binds to the LEDGER-pinned retention (not now+7yr); allow small skew.
        if (head.retainUntil() == null
                || head.retainUntil().isBefore(expectedRetention.minusSeconds(RETENTION_TOLERANCE_SECONDS))) {
            throw anomaly(phase + " retention too short for " + key + ": expected >= " + expectedRetention
                    + " got " + head.retainUntil());
        }
    }

    private void assertLatestIs(String key, String expectedVersionId, String phase) {
        ObjectHead latest = s3.headLatest(key)
                .orElseThrow(() -> anomaly(phase + " latest version missing for " + key));
        if (!expectedVersionId.equals(latest.versionId())) {
            throw anomaly(phase + " latest version moved for " + key + ": expected " + expectedVersionId
                    + " but latest is " + latest.versionId() + " (unexpected new version — fail-closed)");
        }
    }

    private ArchiveAnomalyException anomaly(String message) {
        metrics.anomaly();
        return new ArchiveAnomalyException(message);
    }

    private String deriveObjectKey(long minSeq, long maxSeq) {
        // Deterministic, zero-padded for lexical ordering; ADR-0042 D4.7.
        return String.format("%s/seq-%019d-%019d.ndjson.gz", props.getS3().getKeyPrefix(), minSeq, maxSeq);
    }

    private String tenantAnchorsJson(List<TenantAnchorSnapshot> snapshots) {
        ArrayNode arr = anchorMapper.createArrayNode();
        snapshots.stream()
                .sorted(java.util.Comparator.comparingLong(TenantAnchorSnapshot::tenantId))
                .forEach(a -> {
                    ObjectNode n = arr.addObject();
                    n.put("tenant_id", a.tenantId());
                    n.put("first_prev_hash", a.firstPrevHash());
                    n.put("last_entry_hash", a.lastEntryHash());
                    n.put("first_seq", a.firstSeq());
                    n.put("last_seq", a.lastSeq());
                    n.put("row_count", a.rowCount());
                });
        try {
            return anchorMapper.writeValueAsString(arr);
        } catch (Exception ex) {
            throw new IllegalStateException("tenant_anchors serialization failed", ex);
        }
    }

    private static Instant minEventTimestamp(List<AuditEventRecord> segment) {
        Instant min = segment.get(0).getEventTimestamp();
        for (AuditEventRecord r : segment) {
            if (r.getEventTimestamp() != null && (min == null || r.getEventTimestamp().isBefore(min))) {
                min = r.getEventTimestamp();
            }
        }
        return min;
    }

    private static Instant maxEventTimestamp(List<AuditEventRecord> segment) {
        Instant max = segment.get(0).getEventTimestamp();
        for (AuditEventRecord r : segment) {
            if (r.getEventTimestamp() != null && (max == null || r.getEventTimestamp().isAfter(max))) {
                max = r.getEventTimestamp();
            }
        }
        return max;
    }

    private static String orDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }
}
