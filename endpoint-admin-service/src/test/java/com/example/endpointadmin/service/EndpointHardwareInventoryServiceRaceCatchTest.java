package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHardwareInventorySnapshot;
import com.example.endpointadmin.repository.EndpointHardwareInventorySnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-022 — Mockito unit test for the
 * {@link EndpointHardwareInventoryService} race-catch branch
 * (Codex {@code 019e7007} iter-4 absorb).
 *
 * <p>The H2 slice test proves the idempotency probe happy path; the
 * PG integration test proves the partial UNIQUE condition fires; this
 * test proves the SERVICE'S catch block (
 * {@code catch (DataIntegrityViolationException race) {...}}) maps a
 * concurrent insert collision to the existing row.
 *
 * <p>Mocks: repository + event publisher. The repository is wired so:
 *
 * <ol>
 *   <li>The initial {@code findBySourceCommandResultId} probe returns
 *       empty (no row yet — caller goes to saveAndFlush);</li>
 *   <li>{@code saveAndFlush} throws
 *       {@link DataIntegrityViolationException} (simulated concurrent
 *       insert);</li>
 *   <li>The second {@code findBySourceCommandResultId} inside the
 *       catch returns the existing snapshot (the row the racing
 *       transaction landed first);</li>
 * </ol>
 *
 * <p>Assertions: the catch branch returns that existing snapshot, the
 * event publisher is NOT called (no duplicate event for the loser of
 * the race), and the exception does not propagate.
 */
class EndpointHardwareInventoryServiceRaceCatchTest {

    @Test
    void raceCatchReturnsExistingSnapshotInsteadOfPropagatingTheException() {
        EndpointHardwareInventorySnapshotRepository repository =
                mock(EndpointHardwareInventorySnapshotRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        EndpointHardwareInventoryService service =
                new EndpointHardwareInventoryService(repository, events);

        EndpointDevice device = device(UUID.randomUUID(), UUID.randomUUID());
        EndpointCommand command = command(device);
        EndpointCommandResult result = result(device, command);
        UUID commandResultId = result.getId();

        EndpointHardwareInventorySnapshot existing = new EndpointHardwareInventorySnapshot();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(device.getTenantId());
        existing.setDeviceId(device.getId());
        existing.setSourceCommandResultId(commandResultId);

        // Probe #1 — empty (caller proceeds to persist).
        when(repository.findBySourceCommandResultId(commandResultId))
                .thenReturn(Optional.empty())
                // Probe #2 — inside the catch block — returns the
                // existing row that the racing transaction landed
                // first.
                .thenReturn(Optional.of(existing));

        // saveAndFlush trips the DB partial UNIQUE constraint.
        when(repository.saveAndFlush(any(EndpointHardwareInventorySnapshot.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_endpoint_hardware_inventory_snapshots_source_cmd_result\""));

        Map<String, Object> details = effectiveDetailsWithHardware();

        EndpointHardwareInventorySnapshot returned =
                service.ingest(device, command, result, details);

        // The race-catch branch returned the existing row instead of
        // propagating the exception.
        assertThat(returned).isSameAs(existing);

        // Probe called twice (once before save, once inside catch).
        verify(repository, times(2)).findBySourceCommandResultId(commandResultId);

        // No duplicate event for the race loser.
        verify(events, never()).publishEvent(any());
    }

    @Test
    void raceCatchRethrowsWhenSourceCommandResultIdIsNull() {
        // When commandResultId is NULL there's no way to look up the
        // existing row — the catch branch must propagate the
        // exception so the caller learns about the unrelated
        // integrity violation.
        EndpointHardwareInventorySnapshotRepository repository =
                mock(EndpointHardwareInventorySnapshotRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        EndpointHardwareInventoryService service =
                new EndpointHardwareInventoryService(repository, events);

        EndpointDevice device = device(UUID.randomUUID(), UUID.randomUUID());
        EndpointCommand command = command(device);
        // null command-result → null source_command_result_id
        EndpointCommandResult result = null;

        when(repository.saveAndFlush(any(EndpointHardwareInventorySnapshot.class)))
                .thenThrow(new DataIntegrityViolationException("some other check violated"));

        Map<String, Object> details = effectiveDetailsWithHardware();

        assertThatThrownBy(() ->
                service.ingest(device, command, result, details))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("some other check violated");

        verify(events, never()).publishEvent(any());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static EndpointDevice device(UUID tenantId, UUID deviceId) {
        // Entities use Hibernate-generated UUID PKs (no setId
        // setter); mock so we can pre-seed the id without persisting.
        // NOTE: capture stub return values BEFORE the when() chain
        // to avoid Mockito UnfinishedStubbing when device.get* is
        // called inside another mock's when() argument.
        EndpointDevice device = mock(EndpointDevice.class);
        when(device.getId()).thenReturn(deviceId);
        when(device.getTenantId()).thenReturn(tenantId);
        return device;
    }

    private static EndpointCommand command(EndpointDevice device) {
        UUID commandId = UUID.randomUUID();
        UUID tenantId = device.getTenantId();
        EndpointCommand command = mock(EndpointCommand.class);
        when(command.getId()).thenReturn(commandId);
        when(command.getTenantId()).thenReturn(tenantId);
        when(command.getDevice()).thenReturn(device);
        return command;
    }

    private static EndpointCommandResult result(EndpointDevice device, EndpointCommand command) {
        UUID resultId = UUID.randomUUID();
        UUID tenantId = device.getTenantId();
        EndpointCommandResult result = mock(EndpointCommandResult.class);
        when(result.getId()).thenReturn(resultId);
        when(result.getTenantId()).thenReturn(tenantId);
        when(result.getCommand()).thenReturn(command);
        when(result.getDevice()).thenReturn(device);
        return result;
    }

    private static Map<String, Object> effectiveDetailsWithHardware() {
        Map<String, Object> hw = new LinkedHashMap<>();
        hw.put("schemaVersion", 1);
        hw.put("supported", true);
        hw.put("cpuModel", "Test CPU");
        hw.put("collectedAt", Instant.now().toString());
        hw.put("disks", List.of());
        hw.put("networkInterfaces", List.of());

        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hardware", hw);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", inventory);
        return details;
    }
}
