package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateRolloutFailureRequest;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEventResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureResponse;
import com.example.endpointadmin.model.ClassificationConfidence;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.repository.EndpointRolloutFailureEventRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.service.rolloutfailure.RolloutFailureEvidenceValidator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read + manual-seed surface for the rollout failed-device queue (Faz 22.5 #527
 * slice-1a, contract §2/§3/§4; Codex 019eaaf0). Slice-1a writes ONLY a
 * server-derived initial {@code new} observation (manual operator seed). The
 * auto ingest/classifier (§9.2), transition machine (§4 transitions), stop-line
 * evaluator (§9.3) and escalation generator (§9.4) are deferred slices and are
 * NOT invoked here; no enforcement flag is flipped.
 */
@Service
public class EndpointRolloutFailureService {

    private static final String UNIQUE_ACTIVE_CONSTRAINT = "uq_endpoint_rollout_failure_active";
    private static final String MANUAL_CLASSIFIER_VERSION = "manual:v1";

    // Class-default policy (server-derived; contract §3 owner + §5 retry table).
    private static final Map<RolloutFailureClass, Integer> MAX_RETRIES = Map.of(
            RolloutFailureClass.DNS_EDGE_MTLS, 3,
            RolloutFailureClass.CERT_IDENTITY, 1,
            RolloutFailureClass.INSTALLER_MSI, 2,
            RolloutFailureClass.SERVICE_HMAC_MODE, 2,
            RolloutFailureClass.BACKEND_RESULT_SUBMIT, 3,
            RolloutFailureClass.EDR_NETWORK, 1);

    private static final Map<RolloutFailureClass, String> OWNER_ROLE = Map.of(
            RolloutFailureClass.DNS_EDGE_MTLS, "platform/edge operator",
            RolloutFailureClass.CERT_IDENTITY, "identity/PKI operator",
            RolloutFailureClass.INSTALLER_MSI, "endpoint packaging / IT desktop",
            RolloutFailureClass.SERVICE_HMAC_MODE, "endpoint-agent / backend operator",
            RolloutFailureClass.BACKEND_RESULT_SUBMIT, "backend endpoint-admin owner",
            RolloutFailureClass.EDR_NETWORK, "IT security / network");

    private final EndpointRolloutFailureRepository failureRepository;
    private final EndpointRolloutFailureEventRepository eventRepository;
    private final RolloutFailureEvidenceValidator evidenceValidator;

    public EndpointRolloutFailureService(EndpointRolloutFailureRepository failureRepository,
                                         EndpointRolloutFailureEventRepository eventRepository,
                                         RolloutFailureEvidenceValidator evidenceValidator) {
        this.failureRepository = failureRepository;
        this.eventRepository = eventRepository;
        this.evidenceValidator = evidenceValidator;
    }

    @Transactional
    public RolloutFailureResponse createManual(AdminTenantContext context, CreateRolloutFailureRequest request) {
        UUID orgId = context.tenantId(); // org_id == tenant_id (Faz 21.1)
        RolloutFailureClass failureClass = request.failureClass();
        ClassificationConfidence confidence = request.classificationConfidence();

        JsonNode canonicalEvidence;
        try {
            canonicalEvidence = evidenceValidator.validate(failureClass, request.evidence());
        } catch (RolloutFailureEvidenceValidator.InvalidEvidence ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        // Fast pre-check (the partial-unique index is the race-safe authority).
        failureRepository.findActive(orgId, request.rolloutId(), request.waveId(), request.deviceId())
                .ifPresent(existing -> {
                    throw conflict();
                });

        Instant now = Instant.now();
        EndpointRolloutFailure failure = EndpointRolloutFailure.newManual(
                UUID.randomUUID(), context.tenantId(), request.rolloutId(), request.waveId(),
                request.deviceId(), failureClass, MAX_RETRIES.get(failureClass), confidence,
                MANUAL_CLASSIFIER_VERSION, OWNER_ROLE.get(failureClass), canonicalEvidence, now);

        EndpointRolloutFailureEvent event = EndpointRolloutFailureEvent.detectedManual(
                UUID.randomUUID(), failure, canonicalEvidence, subjectHash(context.subject()), now);

        try {
            failureRepository.saveAndFlush(failure);
            eventRepository.saveAndFlush(event);
        } catch (DataIntegrityViolationException ex) {
            // Only the active-unique race becomes a 409; FK/CHECK stay fail-loud.
            if (mentionsActiveUnique(ex)) {
                throw conflict();
            }
            throw ex;
        }
        return RolloutFailureResponse.from(failure);
    }

    @Transactional(readOnly = true)
    public List<RolloutFailureResponse> listByWave(AdminTenantContext context, String rolloutId, String waveId) {
        return failureRepository
                .findByOrgIdAndRolloutIdAndWaveIdOrderByFirstDetectedAtDescIdDesc(
                        context.tenantId(), rolloutId, waveId)
                .stream().map(RolloutFailureResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RolloutFailureResponse get(AdminTenantContext context, UUID id) {
        return failureRepository.findByIdAndOrgId(id, context.tenantId())
                .map(RolloutFailureResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rollout failure not found"));
    }

    @Transactional(readOnly = true)
    public List<RolloutFailureEventResponse> listEvents(AdminTenantContext context, UUID id) {
        // Resolve the parent within org scope FIRST — never query events by bare
        // failure_id (cross-org leak surface; Codex 019eaaf0).
        failureRepository.findByIdAndOrgId(id, context.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rollout failure not found"));
        return eventRepository
                .findByFailureIdAndOrgIdOrderByCreatedAtAscIdAsc(id, context.tenantId())
                .stream().map(RolloutFailureEventResponse::from).toList();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "an active rollout failure already exists for this wave-device");
    }

    private static boolean mentionsActiveUnique(DataIntegrityViolationException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains(UNIQUE_ACTIVE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private static String subjectHash(String subject) {
        // Server-side stable hash — never persist the raw UPN/email/JWT subject.
        String value = subject == null ? "" : subject;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
