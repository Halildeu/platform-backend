package com.example.endpointadmin.tpmattest;

import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EnrollmentStatus;
import com.example.endpointadmin.repository.EndpointEnrollmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
 */
@Service
public class TpmEnrollmentCompletionService {

    private final EndpointEnrollmentRepository enrollments;
    private final Clock clock;

    public TpmEnrollmentCompletionService(EndpointEnrollmentRepository enrollments, Clock clock) {
        this.enrollments = enrollments;
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

    /** TPM_IN_PROGRESS → CONSUMED (Vault issued). Device-record binding by ek_pub_sha256 is a follow-up. */
    @Transactional
    public void markConsumed(UUID enrollmentId) {
        EndpointEnrollment e = enrollments.findById(enrollmentId)
                .orElseThrow(() -> deny("enrollment no longer present"));
        e.setStatus(EnrollmentStatus.CONSUMED);
        e.setConsumedAt(clock.instant());
        enrollments.save(e);
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
}
