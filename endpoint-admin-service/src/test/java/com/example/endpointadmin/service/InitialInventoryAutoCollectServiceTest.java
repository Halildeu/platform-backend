package com.example.endpointadmin.service;

import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * platform-backend#1206 — decision table of the automatic first inventory.
 * The committed-heartbeat wiring, the lock and the concurrency guarantee are
 * covered against PostgreSQL in {@code InitialInventoryAutoCollectPostgresIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class InitialInventoryAutoCollectServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T06:00:00Z");
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-1206-1206-1206-aaaaaaaaaaaa");
    private static final UUID DEVICE_ID = UUID.fromString("dddddddd-1206-1206-1206-dddddddddddd");

    @Mock private EndpointSoftwareInventorySnapshotRepository snapshotRepository;
    @Mock private EndpointDeviceRepository deviceRepository;
    @Mock private EndpointCommandRepository commandRepository;
    @Mock private EndpointAuditService auditService;

    private InitialInventoryAutoCollectService service;
    private EndpointDevice device;

    @BeforeEach
    void setUp() {
        service = service(true);
        device = new EndpointDevice();
        ReflectionTestUtils.setField(device, "id", DEVICE_ID);
        device.setTenantId(TENANT);
        device.setStatus(DeviceStatus.ONLINE);
    }

    private InitialInventoryAutoCollectService service(boolean enabled) {
        return new InitialInventoryAutoCollectService(snapshotRepository, deviceRepository, commandRepository,
                auditService, Clock.fixed(NOW, ZoneOffset.UTC), enabled,
                Duration.ofMinutes(15), Duration.ofHours(24));
    }

    private void deviceWithoutInventory(List<EndpointCommand> collects) {
        when(snapshotRepository.existsByDevice_Id(DEVICE_ID)).thenReturn(false);
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT, DEVICE_ID)).thenReturn(Optional.of(device));
        when(commandRepository.findByDevice_IdAndCommandTypeOrderByIssuedAtDesc(
                DEVICE_ID, CommandType.COLLECT_INVENTORY)).thenReturn(collects);
    }

    private void stubSave() {
        when(commandRepository.saveAndFlush(any(EndpointCommand.class))).thenAnswer(inv -> {
            EndpointCommand command = inv.getArgument(0);
            ReflectionTestUtils.setField(command, "id", UUID.randomUUID());
            return command;
        });
    }

    private static EndpointCommand collect(CommandStatus status, Instant issuedAt, Instant completedAt) {
        EndpointCommand command = new EndpointCommand();
        command.setCommandType(CommandType.COLLECT_INVENTORY);
        command.setStatus(status);
        command.setIssuedAt(issuedAt);
        command.setIssuedBySubject("admin@example.com");
        command.setCompletedAt(completedAt);
        return command;
    }

    @Test
    void queuesTheSameFullCollectAsTheIslemlerButton_whenTheDeviceHasNoInventoryYet() {
        deviceWithoutInventory(List.of());
        stubSave();

        Optional<UUID> queued = service.enqueueIfNeeded(TENANT, DEVICE_ID);

        assertThat(queued).isPresent();
        ArgumentCaptor<EndpointCommand> captor = ArgumentCaptor.forClass(EndpointCommand.class);
        verify(commandRepository).saveAndFlush(captor.capture());
        EndpointCommand command = captor.getValue();
        assertThat(command.getCommandType()).isEqualTo(CommandType.COLLECT_INVENTORY);
        assertThat(command.getStatus()).isEqualTo(CommandStatus.QUEUED);
        assertThat(command.getApprovalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
        assertThat(command.getDevice()).isSameAs(device);
        assertThat(command.getTenantId()).isEqualTo(TENANT);
        assertThat(command.getIssuedBySubject()).isEqualTo("system:auto-initial-inventory");
        assertThat(command.getIssuedAt()).isEqualTo(NOW);
        assertThat(command.getVisibleAfterAt()).isEqualTo(NOW);
        assertThat(command.getExpiresAt()).as("an offline device must still get it on reconnect").isNull();
        assertThat(command.getIdempotencyKey()).isEqualTo("auto-initial-inventory:" + DEVICE_ID + ":0");

        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        EndpointAdminCommandService.applyCollectInventoryOptIns(expected);
        assertThat(expected).as("the shared full-collect contract").hasSize(11).containsValues(true);
        assertThat(command.getPayload())
                .containsAllEntriesOf(expected)
                .containsEntry("reason", "Automatic first inventory after enrollment")
                .hasSize(12);

        verify(auditService).record(eq(TENANT), eq(device), eq(command), eq("ENDPOINT_COMMAND_CREATED"),
                eq("AUTO_INITIAL_INVENTORY"), eq("system:auto-initial-inventory"),
                eq(command.getIdempotencyKey()), anyMap(), isNull(), anyMap());
    }

    @Test
    void doesNothingOnceAnyInventoryHasReachedTheSystem() {
        when(snapshotRepository.existsByDevice_Id(DEVICE_ID)).thenReturn(true);

        assertThat(service.enqueueIfNeeded(TENANT, DEVICE_ID)).isEmpty();

        verifyNoInteractions(deviceRepository, commandRepository, auditService);
    }

    @Test
    void doesNothingWhenTheSnapshotLandedWhileWaitingForTheDeviceLock() {
        when(snapshotRepository.existsByDevice_Id(DEVICE_ID)).thenReturn(false, true);
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT, DEVICE_ID)).thenReturn(Optional.of(device));

        assertThat(service.enqueueIfNeeded(TENANT, DEVICE_ID)).isEmpty();

        verifyNoInteractions(commandRepository, auditService);
    }

    @ParameterizedTest
    @EnumSource(value = CommandStatus.class, names = {"QUEUED", "DELIVERED", "ACKED", "RUNNING"})
    void waitsForACollectThatIsStillInFlight_whoeverIssuedIt(CommandStatus inFlight) {
        deviceWithoutInventory(List.of(collect(inFlight, NOW.minus(Duration.ofDays(3)), null)));

        assertThat(service.enqueueIfNeeded(TENANT, DEVICE_ID)).isEmpty();

        verify(commandRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void retriesAFailedCollectOnlyAfterTheBackoff() {
        deviceWithoutInventory(List.of(collect(CommandStatus.FAILED,
                NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofMinutes(14)))));

        assertThat(service.enqueueIfNeeded(TENANT, DEVICE_ID)).as("14 min < 15 min backoff").isEmpty();
        verify(commandRepository, never()).saveAndFlush(any());
    }

    @Test
    void retriesAFailedCollectOnceTheBackoffHasPassed_withTheNextAttemptKey() {
        deviceWithoutInventory(List.of(collect(CommandStatus.FAILED,
                NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofMinutes(15)))));
        stubSave();

        assertThat(service.enqueueIfNeeded(TENANT, DEVICE_ID)).isPresent();

        ArgumentCaptor<EndpointCommand> captor = ArgumentCaptor.forClass(EndpointCommand.class);
        verify(commandRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey())
                .isEqualTo("auto-initial-inventory:" + DEVICE_ID + ":1");
    }

    @Test
    void aSucceededCollectThatProducedNoSnapshotIsRetriedToo() {
        EndpointCommand succeeded = collect(CommandStatus.SUCCEEDED,
                NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(1)));
        EndpointCommand failed = collect(CommandStatus.FAILED,
                NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(4)));
        // 2 earlier collects -> the 3rd waits 30 min after the latest settled one.
        deviceWithoutInventory(List.of(succeeded, failed));
        stubSave();

        assertThat(service.enqueueIfNeeded(TENANT, DEVICE_ID)).isPresent();

        ArgumentCaptor<EndpointCommand> captor = ArgumentCaptor.forClass(EndpointCommand.class);
        verify(commandRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).endsWith(":2");
    }

    @Test
    void backoffDoublesPerAttemptAndIsCapped() {
        assertThat(service.backoff(1)).isEqualTo(Duration.ofMinutes(15));
        assertThat(service.backoff(2)).isEqualTo(Duration.ofMinutes(30));
        assertThat(service.backoff(3)).isEqualTo(Duration.ofHours(1));
        assertThat(service.backoff(7)).isEqualTo(Duration.ofHours(16));
        assertThat(service.backoff(8)).as("15 min * 128 = 32 h, capped").isEqualTo(Duration.ofHours(24));
        assertThat(service.backoff(10_000)).as("no overflow on a long-broken device").isEqualTo(Duration.ofHours(24));
    }

    @Test
    void skipsADecommissionedDevice() {
        device.setStatus(DeviceStatus.DECOMMISSIONED);
        when(snapshotRepository.existsByDevice_Id(DEVICE_ID)).thenReturn(false);
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT, DEVICE_ID)).thenReturn(Optional.of(device));

        assertThat(service.enqueueIfNeeded(TENANT, DEVICE_ID)).isEmpty();

        verifyNoInteractions(commandRepository, auditService);
    }

    @Test
    void skipsADeviceOutsideTheTenant() {
        when(snapshotRepository.existsByDevice_Id(DEVICE_ID)).thenReturn(false);
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT, DEVICE_ID)).thenReturn(Optional.empty());

        assertThat(service.enqueueIfNeeded(TENANT, DEVICE_ID)).isEmpty();

        verifyNoInteractions(commandRepository, auditService);
    }

    @Test
    void theFlagTurnsTheWholeFeatureOff() {
        assertThat(service(false).enqueueIfNeeded(TENANT, DEVICE_ID)).isEmpty();

        verifyNoInteractions(snapshotRepository, deviceRepository, commandRepository, auditService);
    }

    @Test
    void neverAsksForAnApprovalOrAReason() {
        deviceWithoutInventory(List.of());
        stubSave();

        service.enqueueIfNeeded(TENANT, DEVICE_ID);

        verify(auditService).record(any(), any(), any(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(metadata ->
                        Boolean.FALSE.equals(metadata.get("requiresApproval"))
                                && "NOT_REQUIRED".equals(metadata.get("approvalStatus"))
                                && Integer.valueOf(0).equals(metadata.get("attempt"))),
                isNull(), anyMap());
    }
}
