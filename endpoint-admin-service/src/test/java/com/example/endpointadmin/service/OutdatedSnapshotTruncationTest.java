package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the {@link OutdatedSnapshotTruncation#isPossiblyTruncated}
 * rule. Locks in the single-source-of-truth contract shared by every
 * outdated-software DTO + the audit event (#1148):
 *
 * <pre>
 *   possiblyTruncated =
 *       upgradeTruncated == TRUE
 *    || (upgradeCount  != null
 *        AND maxUpgrade != null
 *        AND upgradeCount &gt;= maxUpgrade)
 * </pre>
 */
@DisplayName("OutdatedSnapshotTruncation — possiblyTruncated rule (#1148)")
class OutdatedSnapshotTruncationTest {

    private static EndpointOutdatedSoftwareSnapshot snapshot(
            Boolean upgradeTruncated, Integer count, Integer max) {
        EndpointOutdatedSoftwareSnapshot s = new EndpointOutdatedSoftwareSnapshot();
        s.setUpgradeTruncated(upgradeTruncated);
        s.setUpgradeCount(count);
        s.setMaxUpgrade(max);
        return s;
    }

    @Test
    @DisplayName("upgradeTruncated=TRUE → TRUE regardless of count/max (authoritative)")
    void authoritativeTrueWins() {
        // Count well below max + truncated=true → still truncated.
        assertTrue(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(true, 17, 512)));
        // Count > max would also be a fallback hit; truncated=true is
        // sufficient regardless.
        assertTrue(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(true, 999, 512)));
    }

    @Test
    @DisplayName("upgradeTruncated=FALSE + count < max → FALSE (the agent says no)")
    void authoritativeFalseWinsBelowCap() {
        assertFalse(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(false, 17, 512)));
        assertFalse(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(false, 0, 512)));
    }

    @Test
    @DisplayName("upgradeTruncated=FALSE + count == max → TRUE (defence-in-depth fallback)")
    void fallbackAtCap() {
        // Even though the agent says not truncated, count == max is treated
        // as possibly truncated (conservative — the agent may have miscounted
        // by one) to match the bulk LatestEntry rule.
        assertTrue(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(false, 512, 512)));
    }

    @Test
    @DisplayName("upgradeTruncated=FALSE + count > max → TRUE (above-cap aggregate cannot fail-open)")
    void fallbackAboveCap() {
        // Bulk projection / aggregate path may surface a count above the
        // per-snapshot cap; the >= bound keeps the hint stable.
        assertTrue(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(false, 513, 512)));
        assertTrue(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(false, 10_000, 512)));
    }

    @Test
    @DisplayName("upgradeTruncated=null (pre-#40 historical) + count >= max → TRUE")
    void historicalRowFallback() {
        assertTrue(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(null, 512, 512)));
        assertTrue(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(null, 700, 512)));
    }

    @Test
    @DisplayName("upgradeTruncated=null (pre-#40 historical) + count < max → FALSE")
    void historicalRowBelowCap() {
        assertFalse(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(null, 17, 512)));
    }

    @Test
    @DisplayName("Null count or null max → FALSE (cannot evaluate fallback, no authoritative TRUE)")
    void nullScalarsFailClosedToFalse() {
        // Without any signal we cannot claim truncation; rendering "possibly
        // truncated" on a probe that returned nothing would be misleading.
        // (A probeComplete=false snapshot is rendered as "evidence incomplete"
        // separately, not via this flag.)
        assertFalse(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(null, null, null)));
        assertFalse(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(null, null, 512)));
        assertFalse(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(null, 17, null)));
        assertFalse(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(false, null, 512)));
        assertFalse(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(false, 17, null)));
    }

    @Test
    @DisplayName("Pre-#1148 false-positive fix: count == max-1 (just under) → FALSE")
    void justUnderCapIsNotTruncated() {
        // The strict pre-#1148 == form already returned FALSE here; the new
        // form must too (regression guard on the lower bound of >=).
        assertFalse(OutdatedSnapshotTruncation.isPossiblyTruncated(
                snapshot(false, 511, 512)));
    }
}
