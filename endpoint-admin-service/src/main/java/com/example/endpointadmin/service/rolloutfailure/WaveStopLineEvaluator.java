package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.dto.v1.admin.WaveThresholdEvaluation;
import com.example.endpointadmin.model.EndpointRolloutWaveMetricsSnapshot;
import com.example.endpointadmin.repository.EndpointRolloutWaveMetricsSnapshotRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stop-line threshold evaluator (contract §6, Faz 22.5 #527 §9.3). Reads the
 * LATEST orchestrator metrics snapshot for (org, rollout, wave) and computes the
 * COMPUTED + ADVISORY {@code stop_line_status} against §6's formulas:
 * <pre>
 *   wave_failed_percent  = active_failed / active_wave_size * 100  => stop_expansion if &gt; 5
 *   stale_24h_percent    = stale_24h_count / fleet_size * 100      => stop_expansion if &gt; 2
 * </pre>
 * The numerator {@code active_failed} comes from the live queue; the denominators
 * (active_wave_size, fleet_size) and the rollout-scoped stale numerator come from
 * the orchestrator snapshot — the backend cannot derive them. No snapshot →
 * {@link WaveThresholdEvaluation#deferred()}. Status comparisons use integer
 * cross-multiplication (strict {@code >}) so 5.00%/2.00% stay {@code clear}.
 * {@code enforcementFlag} is always false (§6 enforcement deferred).
 */
@Component
public class WaveStopLineEvaluator {

    private static final String STATUS_CLEAR = "clear";
    private static final String STATUS_STOP_EXPANSION = "stop_expansion";
    private static final int WAVE_FAILED_THRESHOLD_PERCENT = 5;
    private static final int STALE_24H_THRESHOLD_PERCENT = 2;

    private final EndpointRolloutWaveMetricsSnapshotRepository snapshotRepository;

    public WaveStopLineEvaluator(EndpointRolloutWaveMetricsSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    public WaveThresholdEvaluation evaluate(UUID tenantId, String rolloutId, String waveId,
                                            long activeFailedCount) {
        EndpointRolloutWaveMetricsSnapshot s = snapshotRepository
                .findFirstByTenantIdAndRolloutIdAndWaveIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                        tenantId, rolloutId, waveId)
                .orElse(null);
        if (s == null) {
            return WaveThresholdEvaluation.deferred();
        }
        int activeWaveSize = s.getActiveWaveSize(); // DB CHECK guarantees > 0
        int fleetSize = s.getFleetSize();           // DB CHECK guarantees > 0
        long stale = s.getStale24hCount();

        // Strict-> via integer cross-multiplication (no float rounding at the boundary).
        boolean waveOver = activeFailedCount * 100L > (long) activeWaveSize * WAVE_FAILED_THRESHOLD_PERCENT;
        boolean staleOver = stale * 100L > (long) fleetSize * STALE_24H_THRESHOLD_PERCENT;
        String status = (waveOver || staleOver) ? STATUS_STOP_EXPANSION : STATUS_CLEAR;

        double waveFailedPercent = activeFailedCount * 100.0 / activeWaveSize;
        double stale24hPercent = stale * 100.0 / fleetSize;

        return WaveThresholdEvaluation.evaluated(
                s.getSourceType(), s.getCapturedAt(), status,
                activeFailedCount, activeWaveSize, waveFailedPercent,
                stale, fleetSize, stale24hPercent);
    }
}
