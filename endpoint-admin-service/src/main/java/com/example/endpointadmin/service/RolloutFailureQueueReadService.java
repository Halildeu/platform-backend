package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.RolloutFailureDetailResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEventResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureItemResponse;
import com.example.endpointadmin.dto.v1.admin.WaveFailureQueueReportResponse;
import com.example.endpointadmin.dto.v1.admin.WaveThresholdEvaluation;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.repository.EndpointRolloutFailureEventRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * #527 — READ projection over the failed-device rollout queue (active counts by
 * class/state). The wave report's thresholdEvaluation is now COMPUTED + ADVISORY
 * (§9.3): when an orchestrator metrics snapshot exists, {@link WaveStopLineEvaluator}
 * computes §6's stop_line_status (enforcement still deferred — enforcementFlag is
 * always false); with no snapshot it is {@code available=false} / deferred.
 */
@Service
public class RolloutFailureQueueReadService {

    private static final String SCHEMA_VERSION = "failed-device-queue/v1";

    private final EndpointRolloutFailureRepository failureRepository;
    private final EndpointRolloutFailureEventRepository eventRepository;
    private final com.example.endpointadmin.service.rolloutfailure.WaveStopLineEvaluator stopLineEvaluator;

    public RolloutFailureQueueReadService(EndpointRolloutFailureRepository failureRepository,
                                          EndpointRolloutFailureEventRepository eventRepository,
                                          com.example.endpointadmin.service.rolloutfailure.WaveStopLineEvaluator stopLineEvaluator) {
        this.failureRepository = failureRepository;
        this.eventRepository = eventRepository;
        this.stopLineEvaluator = stopLineEvaluator;
    }

    /** Active items for a wave, optionally filtered by class / device. */
    @Transactional(readOnly = true)
    public List<RolloutFailureItemResponse> listActive(UUID tenantId, String rolloutId, String waveId,
                                                       RolloutFailureClass classFilter, UUID deviceFilter) {
        return failureRepository
                .findByTenantIdAndRolloutIdAndWaveIdOrderByLastTransitionAtDesc(tenantId, rolloutId, waveId)
                .stream()
                .filter(f -> f.getCurrentState().isActive())
                .filter(f -> classFilter == null || f.getCurrentClass() == classFilter)
                .filter(f -> deviceFilter == null || deviceFilter.equals(f.getDeviceId()))
                .map(RolloutFailureQueueReadService::toItem)
                .toList();
    }

    /** Item + its ordered append-only event ledger (org-scoped). */
    @Transactional(readOnly = true)
    public Optional<RolloutFailureDetailResponse> getDetail(UUID tenantId, UUID failureId) {
        return failureRepository.findByTenantIdAndId(tenantId, failureId).map(failure -> {
            List<RolloutFailureEventResponse> events = eventRepository
                    .findByTenantIdAndFailureIdOrderByCreatedAtAsc(tenantId, failureId)
                    .stream()
                    .map(RolloutFailureQueueReadService::toEvent)
                    .toList();
            return new RolloutFailureDetailResponse(toItem(failure), events);
        });
    }

    /** Wave failure-queue projection report. Active counts only — no evaluator. */
    @Transactional(readOnly = true)
    public WaveFailureQueueReportResponse waveReport(UUID tenantId, String rolloutId, String waveId, Instant generatedAt) {
        List<EndpointRolloutFailure> active = failureRepository
                .findByTenantIdAndRolloutIdAndWaveIdOrderByLastTransitionAtDesc(tenantId, rolloutId, waveId)
                .stream()
                .filter(f -> f.getCurrentState().isActive())
                .toList();

        Map<String, Long> byClass = active.stream()
                .collect(Collectors.groupingBy(f -> f.getCurrentClass().name(), Collectors.counting()));
        Map<String, Long> byState = active.stream()
                .collect(Collectors.groupingBy(f -> f.getCurrentState().wire(), Collectors.counting()));
        long stopLineContribution = active.stream()
                .filter(f -> Boolean.TRUE.equals(f.getStopLineContribution())).count();

        return new WaveFailureQueueReportResponse(
                SCHEMA_VERSION, rolloutId, waveId, generatedAt,
                active.size(), byClass, byState, stopLineContribution,
                stopLineEvaluator.evaluate(tenantId, rolloutId, waveId, active.size()));
    }

    private static RolloutFailureItemResponse toItem(EndpointRolloutFailure f) {
        return new RolloutFailureItemResponse(
                f.getId(), f.getRolloutId(), f.getWaveId(), f.getDeviceId(),
                f.getCurrentClass().name(), f.getCurrentState().wire(),
                f.getRetryCount(), f.getMaxRetries(),
                f.getFirstDetectedAt(), f.getLastObservedAt(), f.getLastTransitionAt(),
                f.getEvidenceRedacted(), f.getOwnerRole(), f.getStopLineContribution(),
                f.getEscalationIssueUrl(), f.getWaiverReason(), f.getWaivedBy(), f.getWaivedUntil(),
                f.getResolvedAt(), f.getResolutionSummary(),
                f.getClassificationConfidence().wire(), f.getClassifierVersion(), f.getVersion());
    }

    private static RolloutFailureEventResponse toEvent(EndpointRolloutFailureEvent e) {
        return new RolloutFailureEventResponse(
                e.getId(), e.getEventType().wire(),
                e.getFromState() == null ? null : e.getFromState().wire(),
                e.getToState().wire(), e.getFailureClass().name(), e.getSourceSignal(),
                e.getRedactedEvidence(), e.getActorType().wire(), e.getActorSubjectHash(),
                e.getClassificationConfidence() == null ? null : e.getClassificationConfidence().wire(),
                e.getCreatedAt());
    }
}
