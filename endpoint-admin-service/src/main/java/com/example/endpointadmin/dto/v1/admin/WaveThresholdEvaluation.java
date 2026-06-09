package com.example.endpointadmin.dto.v1.admin;

/** #527 slice-1 — honesty marker: the stop-line threshold evaluator is DEFERRED
 *  (contract §9.3). Slice-1 reports active-failure COUNTS only; it never emits an
 *  enforced stop_line_status. available is always false until slice-3 lands. */
public record WaveThresholdEvaluation(boolean available, String reason, boolean enforcementFlag) {
    public static WaveThresholdEvaluation deferred() {
        return new WaveThresholdEvaluation(false, "threshold_evaluator_deferred", false);
    }
}
