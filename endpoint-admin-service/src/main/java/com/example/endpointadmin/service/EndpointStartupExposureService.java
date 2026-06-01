package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointStartupExposureApp;
import com.example.endpointadmin.model.EndpointStartupExposureProbeError;
import com.example.endpointadmin.model.EndpointStartupExposureSnapshot;
import com.example.endpointadmin.repository.EndpointStartupExposureSnapshotRepository;
import com.example.endpointadmin.security.StartupExposurePayloadPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BE — startup-exposure inventory ingest + query service (Faz 22.5,
 * AG-040-be). Mirrors AG-039-be {@link EndpointServicesService}.
 * Dual-winner double-lookup invariant + canonical-form hash + retry-
 * idempotent dedupe.
 */
@Service
public class EndpointStartupExposureService {

    private static final Logger log = LoggerFactory.getLogger(EndpointStartupExposureService.class);

    private final EndpointStartupExposureSnapshotRepository repository;
    private final StartupExposurePayloadPolicy policy;

    public EndpointStartupExposureService(
            EndpointStartupExposureSnapshotRepository repository,
            StartupExposurePayloadPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    public static boolean hasStartupExposureBlock(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return false;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("startupExposure") instanceof Map<?, ?>) {
            return true;
        }
        return effectiveDetails.get("startupExposure") instanceof Map<?, ?>;
    }

    @Transactional
    public EndpointStartupExposureSnapshot ingest(
            EndpointDevice device,
            EndpointCommand command,
            EndpointCommandResult result,
            Map<String, Object> effectiveDetails) {

        if (device == null) {
            throw new IllegalArgumentException("device required");
        }
        UUID commandResultId = result != null ? result.getId() : null;

        if (commandResultId != null) {
            Optional<EndpointStartupExposureSnapshot> existing =
                    repository.findBySourceCommandResultId(commandResultId);
            if (existing.isPresent()) {
                // Codex 019e83a8 iter-1 P2#6 absorb: tenant/device
                // cross-check on early short-circuit too — defends
                // against a reused source_command_result_id whose
                // snapshot belongs to a different tenant/device.
                EndpointStartupExposureSnapshot bs = existing.get();
                if (!bs.getTenantId().equals(device.getTenantId())
                        || !bs.getDeviceId().equals(device.getId())) {
                    throw new IllegalStateException(
                            "startup-exposure source command-result tenant/device mismatch on early lookup: snapshot "
                                    + bs.getId() + " (tenant=" + bs.getTenantId()
                                    + ", device=" + bs.getDeviceId() + ") vs request (tenant="
                                    + device.getTenantId() + ", device=" + device.getId() + ")");
                }
                log.debug("Startup-exposure ingest no-op for command_result_id={} (already processed)",
                        commandResultId);
                return bs;
            }
        }

        Map<String, Object> startupExposure = extractStartupExposure(effectiveDetails);
        if (startupExposure == null) {
            throw new IllegalStateException(
                    "ingest called without a startupExposure block — hook should check"
                            + " hasStartupExposureBlock() first");
        }

        StartupExposurePayloadPolicy.Projection projection = policy.projectAndHash(startupExposure);
        String payloadHash = projection.payloadHashSha256();

        Optional<EndpointStartupExposureSnapshot> identical =
                repository.findByTenantDeviceAndPayloadHash(
                        device.getTenantId(), device.getId(), payloadHash,
                        PageRequest.of(0, 1))
                        .stream()
                        .findFirst();
        if (identical.isPresent()) {
            log.debug("Startup-exposure ingest no-op for device_id={} (payload hash unchanged, snapshot_id={})",
                    device.getId(), identical.get().getId());
            return identical.get();
        }

        Instant collectedAt = result != null && result.getReportedAt() != null
                ? result.getReportedAt()
                : Instant.now();

        EndpointStartupExposureSnapshot snapshot = buildSnapshot(
                device, commandResultId, projection, payloadHash, collectedAt);

        UUID insertedId = repository.insertStartupExposureSnapshotOnConflictDoNothing(snapshot);
        if (insertedId == null) {
            Optional<EndpointStartupExposureSnapshot> bySource = commandResultId == null
                    ? Optional.empty()
                    : repository.findBySourceCommandResultId(commandResultId);
            Optional<EndpointStartupExposureSnapshot> byHash =
                    repository.findFirstByTenantIdAndDeviceIdAndPayloadHashSha256OrderByCollectedAtDescCreatedAtDescIdDesc(
                            device.getTenantId(), device.getId(), payloadHash);
            if (bySource.isPresent() && byHash.isPresent()
                    && !bySource.get().getId().equals(byHash.get().getId())) {
                throw new IllegalStateException(
                        "startup-exposure dual-winner invariant breach: source row "
                                + bySource.get().getId() + " != hash row "
                                + byHash.get().getId());
            }
            // Source row tenant/device cross-check.
            if (bySource.isPresent()) {
                EndpointStartupExposureSnapshot bs = bySource.get();
                if (!bs.getTenantId().equals(device.getTenantId())
                        || !bs.getDeviceId().equals(device.getId())) {
                    throw new IllegalStateException(
                            "startup-exposure source command-result tenant/device mismatch: snapshot "
                                    + bs.getId() + " (tenant=" + bs.getTenantId()
                                    + ", device=" + bs.getDeviceId() + ") vs request (tenant="
                                    + device.getTenantId() + ", device=" + device.getId() + ")");
                }
                log.debug("Startup-exposure ingest no-op for command_result_id={} (lost source race)",
                        commandResultId);
                return bs;
            }
            if (byHash.isPresent()) {
                log.debug("Startup-exposure ingest no-op for device_id={} (lost hash race, snapshot_id={})",
                        device.getId(), byHash.get().getId());
                return byHash.get();
            }
            throw new IllegalStateException(
                    "startup-exposure insert no-op without resolvable winner");
        }
        return snapshot;
    }

    public Optional<EndpointStartupExposureSnapshot> findLatest(UUID tenantId, UUID deviceId) {
        return repository.findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                tenantId, deviceId);
    }

    private EndpointStartupExposureSnapshot buildSnapshot(
            EndpointDevice device,
            UUID commandResultId,
            StartupExposurePayloadPolicy.Projection p,
            String payloadHash,
            Instant collectedAt) {
        EndpointStartupExposureSnapshot snapshot = new EndpointStartupExposureSnapshot();
        snapshot.setTenantId(device.getTenantId());
        snapshot.setDeviceId(device.getId());
        snapshot.setSourceCommandResultId(commandResultId);
        snapshot.setSchemaVersion(p.schemaVersion());
        snapshot.setSupported(p.supported());
        snapshot.setProbeComplete(p.probeComplete());
        snapshot.setRdpEnabled(p.rdpEnabled());
        snapshot.setWindowsFirewallEventLogEnabled(p.windowsFirewallEventLogEnabled());
        snapshot.setProbeDurationMs(p.probeDurationMs());
        snapshot.setPayloadHashSha256(payloadHash);
        snapshot.setCollectedAt(collectedAt);

        List<EndpointStartupExposureApp> appEntities = new ArrayList<>(p.startupApps().size());
        for (StartupExposurePayloadPolicy.AppProjection ap : p.startupApps()) {
            EndpointStartupExposureApp a = new EndpointStartupExposureApp();
            a.setRowOrdinal(ap.rowOrdinal());
            a.setName(ap.name());
            a.setLocation(ap.location());
            a.setEnabled(ap.enabled());
            a.setProbeOrigin(ap.probeOrigin());
            a.setTenantId(device.getTenantId());
            a.setSnapshot(snapshot);
            appEntities.add(a);
        }
        snapshot.setStartupApps(appEntities);

        List<EndpointStartupExposureProbeError> errorEntities = new ArrayList<>(p.probeErrors().size());
        for (StartupExposurePayloadPolicy.ProbeErrorProjection pe : p.probeErrors()) {
            EndpointStartupExposureProbeError errEnt = new EndpointStartupExposureProbeError();
            errEnt.setRowOrdinal(pe.rowOrdinal());
            errEnt.setCode(pe.code());
            errEnt.setSource(pe.source());
            errEnt.setSummary(pe.summary());
            errEnt.setTenantId(device.getTenantId());
            errEnt.setSnapshot(snapshot);
            errorEntities.add(errEnt);
        }
        snapshot.setProbeErrors(errorEntities);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractStartupExposure(Map<String, Object> effectiveDetails) {
        if (effectiveDetails == null) {
            return null;
        }
        Object inventory = effectiveDetails.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("startupExposure") instanceof Map<?, ?> s) {
            return (Map<String, Object>) s;
        }
        if (effectiveDetails.get("startupExposure") instanceof Map<?, ?> s2) {
            return (Map<String, Object>) s2;
        }
        return null;
    }
}
