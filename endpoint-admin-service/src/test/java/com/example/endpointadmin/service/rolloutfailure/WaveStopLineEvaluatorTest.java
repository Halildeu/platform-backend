package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.dto.v1.admin.WaveThresholdEvaluation;
import com.example.endpointadmin.model.EndpointRolloutWaveMetricsSnapshot;
import com.example.endpointadmin.repository.EndpointRolloutWaveMetricsSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** §9.3 evaluator: deferred-when-missing + §6 integer-boundary status math. */
class WaveStopLineEvaluatorTest {

    private final EndpointRolloutWaveMetricsSnapshotRepository repo =
            mock(EndpointRolloutWaveMetricsSnapshotRepository.class);
    private final WaveStopLineEvaluator evaluator = new WaveStopLineEvaluator(repo);

    private final UUID tenant = UUID.randomUUID();

    private void snapshot(int activeWaveSize, int fleetSize, int stale24h) {
        EndpointRolloutWaveMetricsSnapshot s = new EndpointRolloutWaveMetricsSnapshot();
        s.setActiveWaveSize(activeWaveSize);
        s.setFleetSize(fleetSize);
        s.setStale24hCount(stale24h);
        s.setCapturedAt(Instant.parse("2026-06-09T09:00:00Z"));
        when(repo.findFirstByTenantIdAndRolloutIdAndWaveIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                any(), any(), any())).thenReturn(Optional.of(s));
    }

    private WaveThresholdEvaluation evaluate(long activeFailed) {
        return evaluator.evaluate(tenant, "rollout-x", "wave-x", activeFailed);
    }

    @Test
    void noSnapshotIsDeferred() {
        when(repo.findFirstByTenantIdAndRolloutIdAndWaveIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        WaveThresholdEvaluation e = evaluate(99);
        assertThat(e.available()).isFalse();
        assertThat(e.reason()).isEqualTo("metrics_snapshot_missing");
        assertThat(e.enforcementFlag()).isFalse();
    }

    @Test
    void belowBothThresholdsIsClear() {
        snapshot(100, 800, 10); // stale 10/800 = 1.25% < 2%
        WaveThresholdEvaluation e = evaluate(4); // 4/100 = 4% < 5%
        assertThat(e.available()).isTrue();
        assertThat(e.enforcementFlag()).isFalse();
        assertThat(e.source()).isEqualTo("orchestrator_snapshot");
        assertThat(e.stopLineStatus()).isEqualTo("clear");
        assertThat(e.waveFailedPercent()).isEqualTo(4.0);
    }

    @Test
    void exactlyFivePercentWaveStaysClear() {
        snapshot(100, 800, 0);
        assertThat(evaluate(5).stopLineStatus()).isEqualTo("clear"); // 5.00% is NOT > 5
    }

    @Test
    void justOverFivePercentWaveIsStopExpansion() {
        snapshot(10000, 80000, 0);
        assertThat(evaluate(501).stopLineStatus()).isEqualTo("stop_expansion"); // 5.01% > 5
    }

    @Test
    void exactlyTwoPercentStaleStaysClear() {
        snapshot(100, 800, 16); // 16/800 = 2.00% is NOT > 2
        assertThat(evaluate(1).stopLineStatus()).isEqualTo("clear");
    }

    @Test
    void justOverTwoPercentStaleIsStopExpansion() {
        snapshot(100, 80000, 1601); // 1601/80000 = 2.00125% > 2
        WaveThresholdEvaluation e = evaluate(1);
        assertThat(e.stopLineStatus()).isEqualTo("stop_expansion");
        assertThat(e.stale24hCount()).isEqualTo(1601L);
    }
}
