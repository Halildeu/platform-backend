package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateRolloutFailureRequest;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureSeedResponse;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureActorType;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureEventType;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.repository.EndpointRolloutFailureEventRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureRepository;
import com.example.endpointadmin.service.rolloutfailure.RolloutFailureEvidenceValidator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Manual operator seed (write) for the rollout failed-device queue (Faz 22.5
 * #527 slice-1b), built on top of #528's read foundation. EVERY non-routing
 * field is server-derived; the operator can only declare WHICH device failed,
 * its class/confidence and the per-class redacted evidence. The evidence is
 * validated by {@link RolloutFailureEvidenceValidator} (the write-side redaction
 * control #528's read foundation lacks) so a raw secret/PII value can never
 * reach the JSONB column. No transition / retry / threshold / escalation side
 * effects — those are the later slices (§9.2–9.4, enforcement flags stay false).
 */
@Service
public class RolloutFailureQueueSeedService {

    static final String CLASSIFIER_VERSION = "manual:v1";
    static final String SOURCE_MANUAL = "manual_operator";
    private static final String ACTIVE_UNIQUE = "ux_erf_active_device";

    // Server-derived per-class policy (contract §3). max_retries + the role that
    // owns the queue item; the operator never sets these.
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

    private final EndpointRolloutFailureRepository failureRepository;
    private final EndpointRolloutFailureEventRepository eventRepository;
    private final RolloutFailureEvidenceValidator evidenceValidator;

    public RolloutFailureQueueSeedService(EndpointRolloutFailureRepository failureRepository,
                                          EndpointRolloutFailureEventRepository eventRepository,
                                          RolloutFailureEvidenceValidator evidenceValidator) {
        this.failureRepository = failureRepository;
        this.eventRepository = eventRepository;
        this.evidenceValidator = evidenceValidator;
    }

    @Transactional
    public RolloutFailureSeedResponse seedManual(UUID tenantId, String subject,
                                                 CreateRolloutFailureRequest request) {
        // Confidence arrives as the contract wire form ("high"|"medium"|"low").
        final RolloutClassificationConfidence confidence;
        try {
            confidence = RolloutClassificationConfidence.fromWire(request.classificationConfidence());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "classificationConfidence must be one of high|medium|low");
        }

        // Fail-closed redaction validation FIRST (400 before any DB work).
        final Map<String, Object> evidence;
        try {
            evidence = evidenceValidator.validateToMap(request.failureClass(), request.evidence());
        } catch (RolloutFailureEvidenceValidator.InvalidEvidence ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        Instant now = Instant.now();
        EndpointRolloutFailure failure = new EndpointRolloutFailure();
        failure.setId(UUID.randomUUID());
        failure.setTenantId(tenantId);
        failure.setRolloutId(request.rolloutId());
        failure.setWaveId(request.waveId());
        failure.setDeviceId(request.deviceId());
        failure.setCurrentClass(request.failureClass());
        failure.setCurrentState(RolloutFailureState.NEW);
        failure.setRetryCount(0);
        failure.setMaxRetries(maxRetriesFor(request.failureClass()));
        failure.setFirstDetectedAt(now);
        failure.setLastObservedAt(now);
        failure.setLastTransitionAt(now);
        failure.setEvidenceRedacted(evidence);
        failure.setOwnerRole(ownerRoleFor(request.failureClass()));
        failure.setClassificationConfidence(confidence);
        failure.setClassifierVersion(CLASSIFIER_VERSION);
        // org_id, created_at, updated_at, version are set by the entity @PrePersist.

        try {
            failureRepository.saveAndFlush(failure);
        } catch (DataIntegrityViolationException ex) {
            if (mentionsActiveUnique(ex)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "an active failure already exists for this device in this wave");
            }
            throw ex; // FK / CHECK violations stay fail-loud
        }

        EndpointRolloutFailureEvent event = new EndpointRolloutFailureEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(tenantId);
        event.setFailureId(failure.getId());
        event.setEventType(RolloutFailureEventType.DETECTED);
        event.setToState(RolloutFailureState.NEW);
        event.setFailureClass(request.failureClass());
        event.setSourceSignal(SOURCE_MANUAL);
        event.setRedactedEvidence(evidence);
        event.setActorType(RolloutFailureActorType.OPERATOR);
        event.setActorSubjectHash(subjectHash(subject));
        event.setClassificationConfidence(confidence);
        eventRepository.saveAndFlush(event);

        return RolloutFailureSeedResponse.from(failure);
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

    private static String subjectHash(String subject) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
