package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateWaveMetricsSnapshotRequest;
import com.example.endpointadmin.model.EndpointRolloutWaveMetricsSnapshot;
import com.example.endpointadmin.repository.EndpointRolloutWaveMetricsSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §9.3 snapshot write — cross-field + future-skew 400 guards (DB CHECK is the backstop). */
class RolloutWaveMetricsSnapshotServiceTest {

    private final EndpointRolloutWaveMetricsSnapshotRepository repo =
            mock(EndpointRolloutWaveMetricsSnapshotRepository.class);
    private final RolloutWaveMetricsSnapshotService service = new RolloutWaveMetricsSnapshotService(repo);

    private final UUID tenant = UUID.randomUUID();

    private CreateWaveMetricsSnapshotRequest req(int wave, int fleet, int stale, Instant capturedAt) {
        return new CreateWaveMetricsSnapshotRequest(wave, fleet, stale, capturedAt, "run-123");
    }

    @Test
    void validSnapshotIsPersisted() {
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        var resp = service.record(tenant, "rollout-x", "wave-x",
                req(50, 800, 10, Instant.parse("2026-06-09T09:00:00Z")));
        assertThat(resp.activeWaveSize()).isEqualTo(50);
        assertThat(resp.fleetSize()).isEqualTo(800);
        verify(repo).saveAndFlush(any(EndpointRolloutWaveMetricsSnapshot.class));
    }

    @Test
    void fleetSmallerThanWaveIs400() {
        assertThatThrownBy(() -> service.record(tenant, "r", "w",
                req(100, 50, 0, Instant.parse("2026-06-09T09:00:00Z"))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void staleGreaterThanFleetIs400() {
        assertThatThrownBy(() -> service.record(tenant, "r", "w",
                req(50, 800, 900, Instant.parse("2026-06-09T09:00:00Z"))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void futureCapturedAtIs400() {
        assertThatThrownBy(() -> service.record(tenant, "r", "w",
                req(50, 800, 10, Instant.now().plusSeconds(3600))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verify(repo, never()).saveAndFlush(any());
    }
}
