package com.example.auditretention.archive;

import com.example.auditretention.audit.AuditChainSupport;
import com.example.auditretention.audit.AuditEventRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — PER_TENANT verify-before-archive
 * (ADR-0042 D4.4 RESOLVED = PER_TENANT, fail-closed).
 *
 * <p>A segment is a contiguous global-{@code seq} window; it interleaves many
 * tenants. The hash-chain is per-tenant, so verification is done per tenant:
 * <ol>
 *   <li><b>anchor continuity</b> — the first row of a tenant in this segment must
 *       link to that tenant's last archived {@code entry_hash} (the authoritative
 *       {@code audit_archive_tenant_anchor} watermark), or be the tenant GENESIS
 *       ({@code prev_hash == null}) iff the tenant has no anchor yet;</li>
 *   <li><b>intra-segment linkage</b> — each subsequent row's {@code prev_hash}
 *       equals the previous same-tenant row's {@code entry_hash};</li>
 *   <li><b>re-hash</b> — every row re-hashes (verbatim {@link AuditChainSupport})
 *       to its stored {@code entry_hash} (tamper-evidence).</li>
 * </ol>
 * Any failure ⇒ {@link Result#broken} (no archive, no cursor advance, alert).
 */
@Component
public class PerTenantChainVerifier {

    /** Authoritative per-tenant watermark (from {@code audit_archive_tenant_anchor}). */
    public record TenantAnchor(long tenantId, String lastEntryHash, long lastArchivedSeq) {
    }

    /** Immutable per-object chain proof snapshot (manifest + ledger {@code tenant_anchors}). */
    public record TenantAnchorSnapshot(long tenantId, String firstPrevHash, String lastEntryHash,
                                       long firstSeq, long lastSeq, long rowCount) {
    }

    public static final class Result {
        private final boolean valid;
        private final String failureReason;
        private final List<TenantAnchorSnapshot> snapshots;

        private Result(boolean valid, String failureReason, List<TenantAnchorSnapshot> snapshots) {
            this.valid = valid;
            this.failureReason = failureReason;
            this.snapshots = snapshots;
        }

        public static Result verified(List<TenantAnchorSnapshot> snapshots) {
            return new Result(true, null, snapshots);
        }

        public static Result broken(String reason) {
            return new Result(false, reason, List.of());
        }

        public boolean isValid() {
            return valid;
        }

        public String failureReason() {
            return failureReason;
        }

        public List<TenantAnchorSnapshot> snapshots() {
            return snapshots;
        }
    }

    /**
     * Verify a segment against the loaded per-tenant anchors.
     *
     * @param segment rows in GLOBAL seq ascending order
     * @param anchors current authoritative watermark per tenant (absent ⇒ tenant never archived)
     */
    public Result verify(List<AuditEventRecord> segment, Map<Long, TenantAnchor> anchors) {
        Map<Long, List<AuditEventRecord>> byTenant = new LinkedHashMap<>();
        for (AuditEventRecord r : segment) {
            byTenant.computeIfAbsent(r.getTenantId(), k -> new ArrayList<>()).add(r);
        }

        List<TenantAnchorSnapshot> snapshots = new ArrayList<>(byTenant.size());
        for (Map.Entry<Long, List<AuditEventRecord>> e : byTenant.entrySet()) {
            long tenantId = e.getKey();
            List<AuditEventRecord> rows = e.getValue(); // seq-ascending (segment was ordered)
            TenantAnchor anchor = anchors.get(tenantId);

            AuditEventRecord first = rows.get(0);
            String expectedPrev;
            if (anchor == null) {
                // First time this tenant is archived. Because the global cursor only
                // advances over a contiguous prefix, EVERY lower-seq row for this tenant
                // is already archived iff an anchor exists; no anchor ⇒ this must be the
                // tenant GENESIS row (prev_hash == null).
                if (first.getPrevHash() != null) {
                    return Result.broken("tenant " + tenantId + " first-seen at seq " + first.getSeq()
                            + " but prev_hash is not null (expected GENESIS) — gap or tamper");
                }
                expectedPrev = null;
            } else {
                if (first.getSeq() <= anchor.lastArchivedSeq()) {
                    return Result.broken("tenant " + tenantId + " segment first seq " + first.getSeq()
                            + " <= already-archived seq " + anchor.lastArchivedSeq() + " — overlap/replay");
                }
                if (first.getPrevHash() == null) {
                    return Result.broken("tenant " + tenantId + " has an anchor but row seq " + first.getSeq()
                            + " claims GENESIS (prev_hash null) — forked chain");
                }
                if (!first.getPrevHash().equals(anchor.lastEntryHash())) {
                    return Result.broken("tenant " + tenantId + " anchor discontinuity at seq " + first.getSeq()
                            + ": prev_hash != last archived entry_hash");
                }
                expectedPrev = anchor.lastEntryHash();
            }

            for (AuditEventRecord r : rows) {
                if (!equalsNullable(expectedPrev, r.getPrevHash())) {
                    return Result.broken("tenant " + tenantId + " chain linkage broken at seq " + r.getSeq()
                            + " (event " + r.getId() + ")");
                }
                String recomputed = AuditChainSupport.computeEntryHash(r.getPrevHash(), r);
                if (!recomputed.equals(r.getEntryHash())) {
                    return Result.broken("tenant " + tenantId + " tamper detected at seq " + r.getSeq()
                            + " (event " + r.getId() + "): stored entry_hash != recomputed");
                }
                expectedPrev = r.getEntryHash();
            }

            snapshots.add(new TenantAnchorSnapshot(
                    tenantId,
                    first.getPrevHash(),
                    expectedPrev, // last entry_hash for this tenant in the segment
                    first.getSeq(),
                    rows.get(rows.size() - 1).getSeq(),
                    rows.size()));
        }
        return Result.verified(snapshots);
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
