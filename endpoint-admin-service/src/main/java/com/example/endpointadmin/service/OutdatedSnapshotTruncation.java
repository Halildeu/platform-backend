package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;

/**
 * Single source of truth for the {@code possiblyTruncated} signal exposed
 * on every admin-side outdated-software DTO (Faz 22.5, #1148).
 *
 * <h4>Rule</h4>
 * The agent's authoritative {@code upgradeTruncated} flag (set by the
 * parser post-platform-agent #40 / commit {@code e64c131}) takes precedence:
 *
 * <pre>
 *   possiblyTruncated =
 *       upgradeTruncated == TRUE
 *    || (upgradeCount  != null
 *        AND maxUpgrade != null
 *        AND upgradeCount &gt;= maxUpgrade)
 * </pre>
 *
 * <p>The {@code &gt;=} fallback is intentionally <strong>widened</strong>
 * from the historical strict {@code ==} heuristic so an above-cap
 * aggregate count (e.g. the bulk path) cannot fail-open the hint — same
 * semantics already used by {@link com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareLatestEntry}.
 *
 * <h4>Why the heuristic is still kept as a fallback</h4>
 * The DB column {@code upgrade_truncated} is {@code BOOLEAN NOT NULL}
 * (V20 migration) and the policy
 * ({@link com.example.endpointadmin.security.OutdatedSoftwarePayloadPolicy})
 * requires the field on every ingest, so in steady state
 * {@code upgradeTruncated} is never null. The bare {@code upgradeCount}
 * &ge; {@code maxUpgrade} branch is a conservative belt-and-braces that
 * keeps the hint stable if a future migration ever relaxed the NOT NULL
 * constraint, or if a derived projection (e.g. a future read replica
 * with eager casts) ever surfaces a null. Cost is one extra null+compare.
 *
 * <h4>What this replaces</h4>
 * The pre-#1148 form in {@link com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareSnapshotResponse},
 * {@link com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareSnapshotSummaryResponse}, and
 * {@link EndpointOutdatedSoftwareService#buildAuditEvent} was:
 *
 * <pre>
 *   upgradeCount != null
 *      AND maxUpgrade != null
 *      AND upgradeCount.equals(maxUpgrade)   // strict ==, no upgradeTruncated check
 * </pre>
 *
 * That shape predates AG-036 / #1147 contract upgradeCount semantics and
 * was a false-positive at exactly {@code maxUpgrade} (a real 512-package
 * host with no truncation would still flip the hint). It also ignored
 * the authoritative {@code upgradeTruncated} signal entirely.
 *
 * <h4>Note on the V20 migration comment</h4>
 * The V20 SQL ({@code upgrade_count} comment lines 92-95) still uses the
 * pre-#40 phrasing ({@code upgrade_count == max_upgrade signals "possibly
 * truncated"}). That comment is intentionally NOT touched here — Flyway
 * pins the checksum of every applied migration, and editing even a comment
 * would force a {@code FlywayValidateException} on every cluster pod
 * already past V20. The source of truth for the runtime rule is this
 * class; the V20 comment is historical context only. Tracked-by:
 * platform-k8s-gitops#1148, Codex thread {@code 019e77df}.
 */
public final class OutdatedSnapshotTruncation {

    private OutdatedSnapshotTruncation() {
        // utility — no instances
    }

    /**
     * @param snapshot the persisted snapshot (must not be {@code null}).
     * @return whether the package list should be rendered with a
     *         "possibly truncated" hint per the rule above.
     */
    public static boolean isPossiblyTruncated(EndpointOutdatedSoftwareSnapshot snapshot) {
        if (Boolean.TRUE.equals(snapshot.getUpgradeTruncated())) {
            return true;
        }
        Integer count = snapshot.getUpgradeCount();
        Integer max = snapshot.getMaxUpgrade();
        return count != null && max != null && count >= max;
    }
}
