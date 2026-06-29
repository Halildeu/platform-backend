package com.example.endpointadmin.dto.v1.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contract-shaped failed-device queue export. This intentionally mirrors
 * docs/contracts/faz-22-failed-device-queue/$defs.waveFailureReport instead of
 * the UI/read-model projection returned by WaveFailureQueueReportResponse.
 */
public record WaveFailureExportResponse(
        String schemaVersion,
        @JsonProperty("rollout_id") String rolloutId,
        @JsonProperty("wave_id") String waveId,
        @JsonProperty("generated_at") Instant generatedAt,
        @JsonProperty("active_wave_size") Integer activeWaveSize,
        @JsonProperty("fleet_size") Integer fleetSize,
        @JsonProperty("wave_failed_count") Long waveFailedCount,
        @JsonProperty("wave_failed_percent") Double waveFailedPercent,
        @JsonProperty("stale_24h_count") Long stale24hCount,
        @JsonProperty("stale_24h_percent") Double stale24hPercent,
        @JsonProperty("stop_line_status") String stopLineStatus,
        @JsonProperty("per_class_counts") Map<String, Long> perClassCounts,
        @JsonProperty("sample_items") List<Item> sampleItems,
        @JsonProperty("escalation_issue_refs") List<String> escalationIssueRefs,
        Enforcement enforcement) {

    public record Item(
            UUID id,
            @JsonProperty("org_id") UUID orgId,
            @JsonProperty("rollout_id") String rolloutId,
            @JsonProperty("wave_id") String waveId,
            @JsonProperty("device_id") UUID deviceId,
            @JsonProperty("current_class") String currentClass,
            @JsonProperty("current_state") String currentState,
            @JsonProperty("retry_count") int retryCount,
            @JsonProperty("max_retries") int maxRetries,
            @JsonProperty("first_detected_at") Instant firstDetectedAt,
            @JsonProperty("last_observed_at") Instant lastObservedAt,
            @JsonProperty("last_transition_at") Instant lastTransitionAt,
            @JsonProperty("evidence_redacted") Map<String, Object> evidenceRedacted,
            @JsonProperty("owner_role") String ownerRole,
            @JsonProperty("stop_line_contribution") Boolean stopLineContribution,
            @JsonProperty("escalation_issue_url") String escalationIssueUrl,
            @JsonProperty("waiver_reason") String waiverReason,
            @JsonProperty("waived_by") String waivedBy,
            @JsonProperty("waived_until") Instant waivedUntil,
            @JsonProperty("resolved_at") Instant resolvedAt,
            @JsonProperty("resolution_summary") String resolutionSummary,
            @JsonProperty("classification_confidence") String classificationConfidence,
            @JsonProperty("classifier_version") String classifierVersion,
            long version) {
    }

    public record Enforcement(
            @JsonProperty("live_ingest") boolean liveIngest,
            @JsonProperty("threshold_evaluator") boolean thresholdEvaluator,
            @JsonProperty("github_escalation_generator") boolean githubEscalationGenerator,
            @JsonProperty("deployment_enforcement_active") boolean deploymentEnforcementActive) {
    }
}
