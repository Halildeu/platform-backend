package com.example.endpointadmin.tpmattest;

import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EndpointTpmDeviceBinding;
import com.example.endpointadmin.model.EnrollmentStatus;
import com.example.endpointadmin.repository.EndpointEnrollmentRepository;
import com.example.endpointadmin.repository.EndpointTpmDeviceBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Faz 22.3B (ADR-0039) gate-4d-2 — the TPM enrollment state machine (Codex {@code 019ec723}
 * REVISE#2). The Vault issuance is an external call that can't be atomic with the DB, so the
 * enrollment transitions in two phases and never fail-open:
 *
 * <pre>
 *   PENDING --markInProgress--> TPM_IN_PROGRESS --markConsumed--> CONSUMED   (Vault success)
 *                                              \--markFailed---> TPM_FAILED  (verify/issue failure)
 * </pre>
 *
 * <p>{@code markInProgress} is set <b>before</b> the Vault call so a re-{@code /nonce} (which only
 * matches {@code PENDING}) can't drive a second issuance. A failed attest goes to {@code TPM_FAILED}
 * — NOT back to {@code PENDING} — bounding retries on a suspicious attempt (paired with the per-scope
 * failed-attest rate-limit). The nonce's single-use remains the authoritative re-entrancy guard.
 *
 * <p>Faz 22.6 #548 slice-1 step-4 (Codex {@code 019efada} decision A): {@code markConsumed} ALSO persists the
 * V10-proven {@link EndpointTpmDeviceBinding} — atomically with the CONSUMED transition — so the canonical
 * device-key SESSION verifier can later bind AK&harr;EK against this enrollment record (see
 * {@link EndpointTpmDeviceBinding}). A null-device enrollment writes NO binding row (a binding with no device key
 * could never be matched at session time); a re-enrollment soft-revokes the prior active binding for the same
 * {@code (tenant, device)} before inserting the new one.
 */
@Service
public class TpmEnrollmentCompletionService {

    private static final Logger log = LoggerFactory.getLogger(TpmEnrollmentCompletionService.class);

    private final EndpointEnrollmentRepository enrollments;
    private final EndpointTpmDeviceBindingRepository bindings;
    private final Clock clock;

    public TpmEnrollmentCompletionService(EndpointEnrollmentRepository enrollments,
                                          EndpointTpmDeviceBindingRepository bindings,
                                          Clock clock) {
        this.enrollments = enrollments;
        this.bindings = bindings;
        this.clock = clock;
    }

    /** PENDING → TPM_IN_PROGRESS. Fail-closed if the enrollment is gone or not PENDING (concurrent/retry). */
    @Transactional
    public void markInProgress(UUID enrollmentId) {
        EndpointEnrollment e = enrollments.findById(enrollmentId)
                .orElseThrow(() -> deny("enrollment no longer present"));
        if (e.getStatus() != EnrollmentStatus.PENDING) {
            throw deny("enrollment not PENDING (already in-progress/consumed/failed)");
        }
        e.setStatus(EnrollmentStatus.TPM_IN_PROGRESS);
        enrollments.save(e);
    }

    /**
     * TPM_IN_PROGRESS → CONSUMED (Vault issued), and persist the V10-proven device binding atomically. The
     * binding row is the enrollment TPM record the canonical session verifier matches AK&harr;EK against.
     */
    @Transactional
    public void markConsumed(UUID enrollmentId, TpmBinding binding) {
        EndpointEnrollment e = enrollments.findById(enrollmentId)
                .orElseThrow(() -> deny("enrollment no longer present"));
        Instant now = clock.instant();
        e.setStatus(EnrollmentStatus.CONSUMED);
        e.setConsumedAt(now);
        enrollments.save(e);
        recordBinding(enrollmentId, binding, now);
    }

    private void recordBinding(UUID enrollmentId, TpmBinding binding, Instant now) {
        if (binding == null || binding.deviceId() == null) {
            // Edge-case 1 (Codex): a null-device enrollment still CONSUMES, but writes NO trustable binding row —
            // the session verifier keys on (tenant, device), so a device-less row could never be matched.
            log.info("TPM_BINDING_SKIPPED_NO_DEVICE_ID enrollment={}", enrollmentId);
            return;
        }
        // Edge-case 2 (Codex): re-enrollment supersede. Soft-revoke the prior active binding with an IMMEDIATE
        // bulk UPDATE (so it hits the DB before the INSERT below — avoids the Hibernate insert-before-update
        // action-queue ordering tripping the partial unique index), then insert the new active row. The partial
        // unique index uq_tpm_binding_active_device is the last-line defense: a concurrent double-active fails
        // closed (DataIntegrityViolationException), never a silent overwrite.
        bindings.revokeActive(binding.tenantId(), binding.deviceId(), now, "REENROLLMENT_SUPERSEDED");
        bindings.save(new EndpointTpmDeviceBinding(
                binding.tenantId(), binding.deviceId(), enrollmentId, binding.akName(),
                binding.akPubSha256(), binding.ekCertSha256(), binding.deviceKeySpkiSha256(), now, now));
    }

    /** TPM_IN_PROGRESS → TPM_FAILED (verify/issue failure). Never clobbers a CONSUMED; never returns to PENDING. */
    @Transactional
    public void markFailed(UUID enrollmentId) {
        enrollments.findById(enrollmentId).ifPresent(e -> {
            if (e.getStatus() == EnrollmentStatus.TPM_IN_PROGRESS) {
                e.setStatus(EnrollmentStatus.TPM_FAILED);
                enrollments.save(e);
            }
        });
    }

    private static TpmAttestException deny(String detail) {
        return new TpmAttestException(TpmDenyCode.DEVICE_NOT_ELIGIBLE, detail);
    }

    /**
     * The V10-proven device-key binding inputs the controller hands to {@link #markConsumed} after a successful
     * {@code /attest}. {@code deviceId} may be null (→ no binding row). {@code akName} is the RAW TPM Name; the
     * AK pub / EK cert / device-key SPKI are SHA-256 hex digests.
     */
    public record TpmBinding(UUID tenantId, UUID deviceId, byte[] akName, String akPubSha256,
                             String ekCertSha256, String deviceKeySpkiSha256) {
    }
}
