package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;

/**
 * Stop-line threshold evaluation (contract §6, Faz 22.5 #527 §9.3). COMPUTED +
 * ADVISORY: {@code enforcementFlag} is ALWAYS false — this never gates a
 * deployment (§6 enforcement stays deferred). {@code available=true} means only
 * "an orchestrator metrics snapshot exists and the evaluator computed the
 * percentages", NOT "enforcement active". When no snapshot exists the evaluation
 * is {@link #deferred()} ({@code available=false}, reason {@code
 * metrics_snapshot_missing}). {@code stopLineStatus ∈ {clear, stop_expansion}};
 * {@code required_review} is reserved (§6 defines no trigger for it).
 */
public record WaveThresholdEvaluation(
        boolean available,
        String reason,
        boolean enforcementFlag,
        String source,
        Instant snapshotCapturedAt,
        String stopLineStatus,
        Long waveFailedCount,
        Integer activeWaveSize,
        Double waveFailedPercent,
        Long stale24hCount,
        Integer fleetSize,
        Double stale24hPercent) {

    public static WaveThresholdEvaluation deferred() {
        return new WaveThresholdEvaluation(false, "metrics_snapshot_missing", false,
                null, null, null, null, null, null, null, null, null);
    }

    public static WaveThresholdEvaluation evaluated(
            String source, Instant snapshotCapturedAt, String stopLineStatus,
            long waveFailedCount, int activeWaveSize, double waveFailedPercent,
            long stale24hCount, int fleetSize, double stale24hPercent) {
        return new WaveThresholdEvaluation(true, null, false,
                source, snapshotCapturedAt, stopLineStatus,
                waveFailedCount, activeWaveSize, waveFailedPercent,
                stale24hCount, fleetSize, stale24hPercent);
    }
}
