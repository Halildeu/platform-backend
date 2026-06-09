package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.Map;

/** #527 slice-1 — a failure-QUEUE projection report (NOT the contract's full
 *  waveFailureReport, which requires the deferred evaluator fields). Active
 *  counts by class/state only; thresholdEvaluation.available=false. */
public record WaveFailureQueueReportResponse(
        String schemaVersion,
        String rolloutId,
        String waveId,
        Instant generatedAt,
        int activeCount,
        Map<String, Long> activeCountByClass,
        Map<String, Long> activeCountByState,
        long stopLineContributionCount,
        WaveThresholdEvaluation thresholdEvaluation) {
}
