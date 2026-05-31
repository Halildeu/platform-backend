package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — flat per-device latest outdated-software entry for the fleet-wide
 * bulk snapshots endpoint (Faz 22.5, #1146). Scalar summary ONLY — NO
 * child {@code packages[]} array (no lazy walk across a fleet-wide
 * fetch), parity with {@link AdminDeviceHealthLatestEntry}.
 *
 * <p>{@code possiblyTruncated} is computed server-side via
 * {@link com.example.endpointadmin.service.OutdatedSnapshotTruncation} —
 * the single source of truth shared with the per-device summary
 * ({@link AdminOutdatedSoftwareSnapshotSummaryResponse}), the full snapshot
 * DTO ({@link AdminOutdatedSoftwareSnapshotResponse}), and the service-level
 * audit event. The rule prefers the agent's authoritative
 * {@code upgradeTruncated} flag (post-platform-agent #40) and falls back to
 * {@code upgradeCount >= maxUpgrade} (defence-in-depth). The web column
 * builder OR-derives the same signal from {@code upgradeCount} /
 * {@code maxUpgrade}, so the bulk path and the per-device path agree.
 */
public record AdminOutdatedSoftwareLatestEntry(
        UUID deviceId,
        Boolean supported,
        Boolean probeComplete,
        Integer upgradeCount,
        Boolean upgradeTruncated,
        Integer maxUpgrade,
        Boolean possiblyTruncated,
        Instant collectedAt) {

    /**
     * Map from the entity reading ONLY scalar getters. MUST NOT call
     * {@code getPackages()} / {@code getProbeErrors()} — no-child-access
     * invariant (see {@link AdminDeviceHealthLatestEntry#from}).
     */
    public static AdminOutdatedSoftwareLatestEntry from(EndpointOutdatedSoftwareSnapshot s) {
        // #1148 DRY: delegate to OutdatedSnapshotTruncation — the single
        // source of truth shared by this bulk LatestEntry, the full snapshot
        // DTO, the per-device summary, and the service-level audit event.
        boolean possiblyTruncated =
                com.example.endpointadmin.service.OutdatedSnapshotTruncation
                        .isPossiblyTruncated(s);
        return new AdminOutdatedSoftwareLatestEntry(
                s.getDeviceId(),
                s.getSupported(),
                s.getProbeComplete(),
                s.getUpgradeCount(),
                s.getUpgradeTruncated(),
                s.getMaxUpgrade(),
                possiblyTruncated,
                s.getCollectedAt());
    }
}
