package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutFailureActorType;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureEventType;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureEventRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Command-result-driven auto-ingest of rollout failed-device-queue items
 * (Faz 22.5 #527 §9.2 slice-2a; Codex 019eaaf0). Reads a committed FAILED/
 * PARTIAL/UNSUPPORTED command result, classifies it (evidence-first), validates
 * the evidence through the same fail-closed {@link RolloutFailureEvidenceValidator}
 * as the manual seed, and seeds OR idempotently coalesces a queue item. EVERY
 * non-evidence field is server-derived (state=new, retry=0, max_retries+owner
 * from a per-class policy, classifier_version=auto:command-result:v1, AUTO actor,
 * source_signal=command_result:&lt;resultId&gt;). NO stop-line / escalation /
 * waive / resolve side effects, and a low-but-valid signal MAY create `new` but
 * NEVER auto-resolves (contract §7). Runs in its OWN transaction so a failure
 * here never affects the originating submitResult (which already committed).
 */
@Service
public class RolloutFailureAutoIngestService {

    static final String CLASSIFIER_VERSION = "auto:command-result:v1";
    private static final String ACTIVE_UNIQUE = "ux_erf_active_device";
    private static final EnumSet<RolloutFailureState> ACTIVE =
            EnumSet.of(RolloutFailureState.NEW, RolloutFailureState.RETRYING,
                    RolloutFailureState.QUARANTINED, RolloutFailureState.ESCALATED);

    private static int maxRetriesFor(RolloutFailureClass c) {
        return switch (c) {
            case DNS_EDGE_MTLS, BACKEND_RESULT_SUBMIT -> 3;
            case INSTALLER_MSI, SERVICE_HMAC_MODE -> 2;
            case CERT_IDENTITY, EDR_NETWORK -> 1;
        };
    }

    private static String ownerRoleFor(RolloutFailureClass c) {
        return switch (c) {
            case DNS_EDGE_MTLS -> "platform-edge";
            case CERT_IDENTITY -> "security-pki";
            case INSTALLER_MSI -> "endpoint-packaging";
            case SERVICE_HMAC_MODE -> "platform-agent";
            case BACKEND_RESULT_SUBMIT -> "platform-backend";
            case EDR_NETWORK -> "security-network";
        };
    }

    private final EndpointCommandResultRepository resultRepository;
    private final EndpointRolloutFailureRepository failureRepository;
    private final EndpointRolloutFailureEventRepository eventRepository;
    private final RolloutFailureClassifier classifier;
    private final RolloutFailureEvidenceValidator validator;

    public RolloutFailureAutoIngestService(EndpointCommandResultRepository resultRepository,
                                           EndpointRolloutFailureRepository failureRepository,
                                           EndpointRolloutFailureEventRepository eventRepository,
                                           RolloutFailureClassifier classifier,
                                           RolloutFailureEvidenceValidator validator) {
        this.resultRepository = resultRepository;
        this.failureRepository = failureRepository;
        this.eventRepository = eventRepository;
        this.classifier = classifier;
        this.validator = validator;
    }

    /** @return true if a queue item was created or coalesced; false on skip/no-op. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean ingest(CommandResultFailedEvent ev) {
        EndpointCommandResult result = resultRepository.findById(ev.commandResultId()).orElse(null);
        if (result == null || result.getResultStatus() == CommandResultStatus.SUCCEEDED) {
            return false; // gone or not actually a failure → skip
        }

        RolloutFailureClassifier.Signal signal = new RolloutFailureClassifier.Signal(
                ev.deviceId(), null /* command_id evidence is nullable; FK not exposed as a scalar */,
                ev.commandType(), result.getErrorCode(), result.getExitCode(),
                result.getErrorMessage() != null && !result.getErrorMessage().isBlank(),
                result.getResultPayload());
        Optional<RolloutFailureClassifier.Classified> maybe = classifier.classify(signal);
        if (maybe.isEmpty()) {
            return false; // no evidence-satisfiable class → skip (contract §7: do not force)
        }
        RolloutFailureClassifier.Classified c = maybe.get();
        UUID tenantId = ev.tenantId();
        String rolloutId = "cmd-result-auto:" + ev.commandType().name(); // stable virtual context
        String waveId = c.failureClass().name();                          // NOT a time bucket
        String sourceSignal = "command_result:" + ev.commandResultId();
        return ingestClassified(tenantId, ev.deviceId(), rolloutId, waveId,
                c, CLASSIFIER_VERSION, sourceSignal, Instant.now());
    }

    /**
     * Shared fail-closed auto-writer for autonomous queue signals. Signal-specific
     * classifiers own truthful evidence construction; this method owns the common
     * queue policy: schema validation, active-row idempotency, active partial
     * unique race handling, and append-only detected/retry ledger rows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean ingestClassified(UUID tenantId, UUID deviceId, String rolloutId, String waveId,
                                    RolloutFailureClassifier.Classified c, String classifierVersion,
                                    String sourceSignal, Instant now) {
        final Map<String, Object> evidence;
        try {
            evidence = validator.validateToMap(c.failureClass(), c.evidence());
        } catch (RolloutFailureEvidenceValidator.InvalidEvidence ex) {
            return false; // classifier produced non-validatable evidence → fail-closed skip
        }

        Optional<EndpointRolloutFailure> active =
                findActive(tenantId, rolloutId, waveId, deviceId);
        if (active.isPresent()) {
            return coalesce(active.get(), c, evidence, sourceSignal, now);
        }
        return createNew(tenantId, deviceId, rolloutId, waveId, c, classifierVersion,
                evidence, sourceSignal, now);
    }

    private boolean createNew(UUID tenantId, UUID deviceId, String rolloutId, String waveId,
                              RolloutFailureClassifier.Classified c, String classifierVersion,
                              Map<String, Object> evidence, String sourceSignal, Instant now) {
        EndpointRolloutFailure f = new EndpointRolloutFailure();
        f.setId(UUID.randomUUID());
        f.setTenantId(tenantId);
        f.setRolloutId(rolloutId);
        f.setWaveId(waveId);
        f.setDeviceId(deviceId);
        f.setCurrentClass(c.failureClass());
        f.setCurrentState(RolloutFailureState.NEW);
        f.setRetryCount(0);
        f.setMaxRetries(maxRetriesFor(c.failureClass()));
        f.setFirstDetectedAt(now);
        f.setLastObservedAt(now);
        f.setLastTransitionAt(now);
        f.setEvidenceRedacted(evidence);
        f.setOwnerRole(ownerRoleFor(c.failureClass()));
        f.setClassificationConfidence(c.confidence());
        f.setClassifierVersion(classifierVersion);
        try {
            failureRepository.saveAndFlush(f);
        } catch (DataIntegrityViolationException ex) {
            if (mentionsActiveUnique(ex)) {
                // Lost a race against a concurrent ingest — coalesce onto the winner.
                return findActive(tenantId, rolloutId, waveId, deviceId)
                        .map(existing -> coalesce(existing, c, evidence, sourceSignal, now))
                        .orElse(false);
            }
            throw ex;
        }
        EndpointRolloutFailureEvent e = new EndpointRolloutFailureEvent();
        e.setId(UUID.randomUUID());
        e.setTenantId(tenantId);
        e.setFailureId(f.getId());
        e.setEventType(RolloutFailureEventType.DETECTED);
        e.setToState(RolloutFailureState.NEW);
        e.setFailureClass(c.failureClass());
        e.setSourceSignal(sourceSignal);
        e.setRedactedEvidence(evidence);
        e.setActorType(RolloutFailureActorType.AUTO);
        e.setClassificationConfidence(c.confidence());
        eventRepository.saveAndFlush(e);
        return true;
    }

    private boolean coalesce(EndpointRolloutFailure f, RolloutFailureClassifier.Classified c,
                             Map<String, Object> evidence, String sourceSignal, Instant now) {
        // Idempotency: if THIS source result was already ledgered, no-op (replay /
        // double-listener safe).
        if (eventRepository.existsByTenantIdAndFailureIdAndSourceSignal(
                f.getTenantId(), f.getId(), sourceSignal)) {
            return false;
        }
        // A distinct failing observation on an already-active item bumps last-observed.
        f.setLastObservedAt(now);
        failureRepository.saveAndFlush(f);

        // Contract §4 state machine: the only valid self-loop is retrying -> retrying.
        // For NEW/QUARANTINED/ESCALATED a repeat observation is NOT a transition, so
        // it bumps last-observed only (no invalid ledger event). retry_count is the
        // rollout-retry counter — NOT touched here.
        if (f.getCurrentState() == RolloutFailureState.RETRYING) {
            EndpointRolloutFailureEvent e = new EndpointRolloutFailureEvent();
            e.setId(UUID.randomUUID());
            e.setTenantId(f.getTenantId());
            e.setFailureId(f.getId());
            e.setEventType(RolloutFailureEventType.RETRY);
            e.setFromState(RolloutFailureState.RETRYING);
            e.setToState(RolloutFailureState.RETRYING);
            e.setFailureClass(c.failureClass());
            e.setSourceSignal(sourceSignal);
            e.setRedactedEvidence(evidence);
            e.setActorType(RolloutFailureActorType.AUTO);
            e.setClassificationConfidence(c.confidence());
            eventRepository.saveAndFlush(e);
        }
        return true;
    }

    private Optional<EndpointRolloutFailure> findActive(UUID tenantId, String rolloutId,
                                                        String waveId, UUID deviceId) {
        return failureRepository
                .findByTenantIdAndRolloutIdAndWaveIdAndDeviceId(tenantId, rolloutId, waveId, deviceId)
                .stream()
                .filter(r -> ACTIVE.contains(r.getCurrentState()))
                .findFirst();
    }

    private static boolean mentionsActiveUnique(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains(ACTIVE_UNIQUE)) {
                return true;
            }
        }
        return false;
    }
}
