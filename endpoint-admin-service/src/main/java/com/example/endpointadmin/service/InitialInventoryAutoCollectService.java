package com.example.endpointadmin.service;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * platform-backend#1206 — a newly enrolled device gets its first inventory
 * without an admin pressing "Envanteri Şimdi Topla".
 *
 * <p>Called after every committed heartbeat of a {@code COLLECT_INVENTORY}
 * capable agent (see {@link InitialInventoryAutoCollectListener}). It queues one
 * system-issued full {@code COLLECT_INVENTORY} — the same payload the İşlemler
 * tab sends — only while the device:
 * <ul>
 *   <li>has no software-inventory snapshot yet, i.e. nothing has reached the
 *       device drawer;</li>
 *   <li>has no {@code COLLECT_INVENTORY} in flight, whoever issued it; and</li>
 *   <li>is past the retry backoff of its previous collect, if there was one.</li>
 * </ul>
 * Once the first snapshot exists it never fires again for that device; later
 * collections stay operator-initiated, as before.
 *
 * <p>Retry: the n-th collect for a device waits {@code retryBase * 2^(n-1)},
 * capped at {@code retryMax}, after the previous one settled. A device that can
 * never produce a snapshot therefore costs at most one command per
 * {@code retryMax}, not one per heartbeat. The command carries no expiry, so an
 * offline device receives it when it reconnects instead of getting a new one.
 */
@Service
@ConditionalOnPrimaryEndpointPlane
public class InitialInventoryAutoCollectService {

    static final String SYSTEM_SUBJECT = "system:auto-initial-inventory";
    static final String REASON = "Automatic first inventory after enrollment";
    static final String AUDIT_ACTION = "AUTO_INITIAL_INVENTORY";
    private static final String IDEMPOTENCY_PREFIX = "auto-initial-inventory:";
    private static final int DEFAULT_PRIORITY = 100;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final Set<CommandStatus> IN_FLIGHT = EnumSet.of(
            CommandStatus.QUEUED,
            CommandStatus.DELIVERED,
            CommandStatus.ACKED,
            CommandStatus.RUNNING);

    private final EndpointSoftwareInventorySnapshotRepository snapshotRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final EndpointCommandRepository commandRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;
    private final boolean enabled;
    private final Duration retryBase;
    private final Duration retryMax;

    public InitialInventoryAutoCollectService(
            EndpointSoftwareInventorySnapshotRepository snapshotRepository,
            EndpointDeviceRepository deviceRepository,
            EndpointCommandRepository commandRepository,
            EndpointAuditService auditService,
            Clock clock,
            @Value("${endpoint-admin.commands.auto-initial-inventory.enabled:true}") boolean enabled,
            @Value("${endpoint-admin.commands.auto-initial-inventory.retry-base:PT15M}") Duration retryBase,
            @Value("${endpoint-admin.commands.auto-initial-inventory.retry-max:PT24H}") Duration retryMax) {
        this.snapshotRepository = snapshotRepository;
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.enabled = enabled;
        this.retryBase = positiveOr(retryBase, Duration.ofMinutes(15));
        this.retryMax = positiveOr(retryMax, Duration.ofHours(24));
    }

    /**
     * Queue the device's first inventory collection if it still has none.
     *
     * @return the id of the queued command, or empty when nothing was needed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> enqueueIfNeeded(UUID tenantId, UUID deviceId) {
        if (!enabled || tenantId == null || deviceId == null) {
            return Optional.empty();
        }
        // Every heartbeat of every device reaches this line, so the common case
        // (inventory already present) must stay a single indexed lookup.
        if (snapshotRepository.existsByDevice_Id(deviceId)) {
            return Optional.empty();
        }
        // Same PESSIMISTIC_WRITE the admin command path takes: serialises this
        // against concurrent heartbeats of the device and against a decommission
        // cascade, so neither a duplicate nor an orphan command can slip in.
        EndpointDevice device = deviceRepository.findVisibleToOrgAndIdForUpdate(tenantId, deviceId)
                .orElse(null);
        if (device == null || device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            return Optional.empty();
        }
        // A result may have committed while this transaction waited for the lock.
        if (snapshotRepository.existsByDevice_Id(deviceId)) {
            return Optional.empty();
        }

        List<EndpointCommand> collects = commandRepository
                .findByDevice_IdAndCommandTypeOrderByIssuedAtDesc(deviceId, CommandType.COLLECT_INVENTORY);
        if (collects.stream().anyMatch(command -> IN_FLIGHT.contains(command.getStatus()))) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        int attempt = collects.size();
        if (attempt > 0 && now.isBefore(settledAt(collects.get(0)).plus(backoff(attempt)))) {
            return Optional.empty();
        }

        String idempotencyKey = CommandIdempotencyKeys.build(
                IDEMPOTENCY_PREFIX + deviceId + ":", Integer.toString(attempt), 16);
        Map<String, Object> payload = new LinkedHashMap<>();
        EndpointAdminCommandService.applyCollectInventoryOptIns(payload);
        payload.put("reason", REASON);

        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(CommandType.COLLECT_INVENTORY);
        command.setIdempotencyKey(idempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        command.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
        command.setPayload(payload);
        command.setPriority(DEFAULT_PRIORITY);
        command.setAttemptCount(0);
        command.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
        command.setVisibleAfterAt(now);
        command.setIssuedBySubject(SYSTEM_SUBJECT);
        command.setIssuedAt(now);
        EndpointCommand saved = commandRepository.saveAndFlush(command);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("commandType", CommandType.COLLECT_INVENTORY.name());
        metadata.put("idempotencyKey", idempotencyKey);
        metadata.put("priority", DEFAULT_PRIORITY);
        metadata.put("maxAttempts", DEFAULT_MAX_ATTEMPTS);
        metadata.put("requiresApproval", false);
        metadata.put("approvalStatus", ApprovalStatus.NOT_REQUIRED.name());
        metadata.put("issuerSubject", SYSTEM_SUBJECT);
        metadata.put("reason", REASON);
        metadata.put("attempt", attempt);
        auditService.record(
                tenantId,
                device,
                saved,
                "ENDPOINT_COMMAND_CREATED",
                AUDIT_ACTION,
                SYSTEM_SUBJECT,
                idempotencyKey,
                metadata,
                null,
                Map.of("status", saved.getStatus().name(),
                        "approvalStatus", saved.getApprovalStatus().name()));
        return Optional.of(saved.getId());
    }

    /** Wait before the {@code attempt}-th collect (1-based count of earlier ones). */
    Duration backoff(int attempt) {
        Duration wait = retryBase.multipliedBy(1L << Math.min(Math.max(attempt - 1, 0), 20));
        return wait.compareTo(retryMax) > 0 ? retryMax : wait;
    }

    private static Instant settledAt(EndpointCommand command) {
        if (command.getCompletedAt() != null) {
            return command.getCompletedAt();
        }
        if (command.getUpdatedAt() != null) {
            return command.getUpdatedAt();
        }
        return command.getIssuedAt();
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
