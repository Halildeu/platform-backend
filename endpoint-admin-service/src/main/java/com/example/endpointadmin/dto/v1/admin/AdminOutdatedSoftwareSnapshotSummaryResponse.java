package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * BE — summary projection used by the outdated-software history list
 * endpoint (Faz 22.5, AG-036 query API). Mirrors the AG-033
 * {@code AdminDeviceHealthSnapshotSummaryResponse}.
 *
 * <p>No child {@code packages[]} array so a 50-row page does not amplify
 * into hundreds of package rows on the wire. The full snapshot (with
 * packages) is fetched via the {@code /latest} endpoint.
 *
 * <p>The summary surfaces the upgrade count + truncation flags + the
 * "possibly truncated" signal so the history accordion can flag at-risk
 * snapshots (many pending upgrades / incomplete probe / possibly truncated)
 * without loading the child collection.
 */
public record AdminOutdatedSoftwareSnapshotSummaryResponse(
        UUID id,
        UUID deviceId,
        Short schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Integer upgradeCount,
        Boolean upgradeTruncated,
        Boolean possiblyTruncated,
        Integer maxUpgrade,
        String sourceUsed,
        Integer packageCount,
        Integer probeErrorCount,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt) {

    public static AdminOutdatedSoftwareSnapshotSummaryResponse from(EndpointOutdatedSoftwareSnapshot snapshot) {
        int packageCount = snapshot.getPackages() != null ? snapshot.getPackages().size() : 0;
        int errorCount = snapshot.getProbeErrors() != null ? snapshot.getProbeErrors().size() : 0;
        // #1148: prefer authoritative upgradeTruncated, fall back to
        // upgradeCount >= maxUpgrade. Single source of truth shared across
        // the full snapshot DTO, this summary, the bulk LatestEntry, and the
        // service-level audit event.
        boolean possiblyTruncated =
                com.example.endpointadmin.service.OutdatedSnapshotTruncation
                        .isPossiblyTruncated(snapshot);
        return new AdminOutdatedSoftwareSnapshotSummaryResponse(
                snapshot.getId(),
                snapshot.getDeviceId(),
                snapshot.getSchemaVersion(),
                snapshot.getSupported(),
                snapshot.getProbeComplete(),
                snapshot.getUpgradeCount(),
                snapshot.getUpgradeTruncated(),
                possiblyTruncated,
                snapshot.getMaxUpgrade(),
                snapshot.getSourceUsed(),
                packageCount,
                errorCount,
                snapshot.getPayloadHashSha256(),
                snapshot.getCollectedAt(),
                snapshot.getCreatedAt());
    }
}
