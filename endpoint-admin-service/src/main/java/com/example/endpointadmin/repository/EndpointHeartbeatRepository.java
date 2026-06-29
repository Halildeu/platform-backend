package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointHeartbeat;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointHeartbeatRepository extends JpaRepository<EndpointHeartbeat, UUID> {

    List<EndpointHeartbeat> findTop20ByDevice_IdOrderByReceivedAtDesc(UUID deviceId);

    /**
     * AG-028 Phase 1b — return the single most recent heartbeat row for a
     * device. Used by {@code EndpointUninstallService.approve} to evaluate
     * heartbeat freshness AND capability advertisement from the same row
     * (Codex post-impl iter-1 absorb, thread `019e8dcd` must-fix #3).
     *
     * <p>{@code device.lastSeenAt} is updated by both heartbeat AND
     * enrollment/cert-rotate paths, so it is NOT a reliable proxy for "an
     * agent that advertised capabilities recently." Reading the most recent
     * heartbeat row directly ties the freshness check (its
     * {@code receivedAt}) and the capability check (its
     * {@code payload.capabilities}) to the same source of truth.
     */
    Optional<EndpointHeartbeat>
        findFirstByDevice_IdOrderByReceivedAtDesc(UUID deviceId);

    /**
     * #527 slice-2b — heartbeat-stale auto-ingest source query. Returns only the
     * latest heartbeat row per device, and only when that latest row is older
     * than the stale cutoff. This deliberately does not use
     * {@code EndpointDevice.lastSeenAt}, which is also updated by non-heartbeat
     * lifecycle paths and is therefore too broad for heartbeat-staleness evidence.
     */
    @Query("""
            select h
            from EndpointHeartbeat h
            join fetch h.device d
            where d.status <> :excludedStatus
              and h.receivedAt = (
                select max(h2.receivedAt)
                from EndpointHeartbeat h2
                where h2.device.id = d.id
              )
              and h.receivedAt < :cutoff
            order by h.receivedAt asc
            """)
    List<EndpointHeartbeat> findLatestStaleHeartbeats(
            @Param("cutoff") Instant cutoff,
            @Param("excludedStatus") DeviceStatus excludedStatus,
            Pageable pageable);
}
