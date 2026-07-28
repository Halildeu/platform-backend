package com.example.endpointadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointServicesSnapshot;
import com.example.endpointadmin.repository.EndpointServicesSnapshotRepository;
import com.example.endpointadmin.security.ServicesPayloadPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EndpointServicesServiceTest {

    @Test
    void replayedCommandResultReturnsExistingObservation() {
        EndpointServicesSnapshotRepository repository =
                mock(EndpointServicesSnapshotRepository.class);
        ServicesPayloadPolicy policy = mock(ServicesPayloadPolicy.class);
        EndpointServicesService service = new EndpointServicesService(repository, policy);

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        ReflectionTestUtils.setField(device, "id", deviceId);

        EndpointCommandResult result =
                commandResult(resultId, Instant.parse("2026-07-28T06:00:00Z"));
        EndpointServicesSnapshot existing = new EndpointServicesSnapshot();
        existing.setTenantId(tenantId);
        existing.setDeviceId(deviceId);
        existing.setSourceCommandResultId(resultId);

        when(repository.findBySourceCommandResultId(resultId))
                .thenReturn(Optional.of(existing));

        EndpointServicesSnapshot actual = service.ingest(
                device,
                null,
                result,
                Map.of("inventory", Map.of("services", Map.of("schemaVersion", 1))));

        assertThat(actual).isSameAs(existing);
        verify(policy, never()).projectAndHash(any());
        verify(repository, never()).insertServicesSnapshotOnConflictDoNothing(any());
    }

    @Test
    void unchangedPayloadFromNewCommandResultCreatesFreshObservation() {
        EndpointServicesSnapshotRepository repository =
                mock(EndpointServicesSnapshotRepository.class);
        ServicesPayloadPolicy policy = mock(ServicesPayloadPolicy.class);
        EndpointServicesService service = new EndpointServicesService(repository, policy);

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        ReflectionTestUtils.setField(device, "id", deviceId);

        EndpointCommandResult firstResult = commandResult(
                UUID.randomUUID(), Instant.parse("2026-07-28T06:00:00Z"));
        EndpointCommandResult secondResult = commandResult(
                UUID.randomUUID(), Instant.parse("2026-07-28T06:05:00Z"));

        Map<String, Object> details = Map.of(
                "inventory", Map.of("services", Map.of("schemaVersion", 1)));
        ServicesPayloadPolicy.Projection projection =
                new ServicesPayloadPolicy.Projection(
                        1,
                        true,
                        true,
                        10,
                        List.of(),
                        List.of(),
                        "a".repeat(64));

        when(policy.projectAndHash(any())).thenReturn(projection);
        when(repository.findBySourceCommandResultId(any())).thenReturn(Optional.empty());
        when(repository.insertServicesSnapshotOnConflictDoNothing(any()))
                .thenReturn(UUID.randomUUID(), UUID.randomUUID());

        EndpointServicesSnapshot first =
                service.ingest(device, null, firstResult, details);
        EndpointServicesSnapshot second =
                service.ingest(device, null, secondResult, details);

        assertThat(first.getPayloadHashSha256()).isEqualTo(second.getPayloadHashSha256());
        assertThat(first.getCollectedAt()).isBefore(second.getCollectedAt());
        assertThat(first.getSourceCommandResultId())
                .isNotEqualTo(second.getSourceCommandResultId());
        verify(repository, times(2)).insertServicesSnapshotOnConflictDoNothing(any());
    }

    private static EndpointCommandResult commandResult(UUID id, Instant reportedAt) {
        EndpointCommandResult result = new EndpointCommandResult();
        ReflectionTestUtils.setField(result, "id", id);
        result.setReportedAt(reportedAt);
        return result;
    }
}
