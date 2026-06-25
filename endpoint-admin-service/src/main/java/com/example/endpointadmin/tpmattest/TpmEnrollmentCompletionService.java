package com.example.endpointadmin.tpmattest;

import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EndpointTpmDeviceBinding;
import com.example.endpointadmin.model.EnrollmentStatus;
import com.example.endpointadmin.repository.EndpointEnrollmentRepository;
import com.example.endpointadmin.repository.EndpointTpmDeviceBindingRepository;
import com.example.endpointadmin.security.TpmVaultCertExtractor;
import com.example.endpointadmin.service.TpmDeviceCompletionService;
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
 * {@link EndpointTpmDeviceBinding}). Phase 1.5 ({@code 019eff93}) delegates device resolution to
 * {@link TpmDeviceCompletionService}: a device-less (system-native) enrollment now COMPLETES a canonical device
 * (rather than CONSUMING with no binding), so a trustable binding row is ALWAYS written; a re-enrollment
 * soft-revokes the prior active binding for the same {@code (tenant, device)} before inserting the new one.
 */
@Service
public class TpmEnrollmentCompletionService {

    private static final Logger log = LoggerFactory.getLogger(TpmEnrollmentCompletionService.class);

    private final EndpointEnrollmentRepository enrollments;
    private final EndpointTpmDeviceBindingRepository bindings;
    private final TpmDeviceCompletionService deviceCompletion;
    private final Clock clock;

    public TpmEnrollmentCompletionService(EndpointEnrollmentRepository enrollments,
                                          EndpointTpmDeviceBindingRepository bindings,
                                          TpmDeviceCompletionService deviceCompletion,
                                          Clock clock) {
        this.enrollments = enrollments;
        this.bindings = bindings;
        this.deviceCompletion = deviceCompletion;
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
    public void markConsumed(UUID enrollmentId, TpmCompletion completion) {
        EndpointEnrollment e = enrollments.findById(enrollmentId)
                .orElseThrow(() -> deny("enrollment no longer present"));
        Instant now = clock.instant();
        e.setStatus(EnrollmentStatus.CONSUMED);
        e.setConsumedAt(now);
        // Phase 1.5 (Codex 019eff93) — complete the device ATOMICALLY (device-less -> created/adopted;
        // pre-bound -> asserted) + register/rotate the VAULT_TPM cert + link enrollment.device. The resolved
        // device id is never null, so recordBinding ALWAYS writes a trustable binding row.
        UUID deviceId = deviceCompletion.complete(
                completion.tenantId(), completion.ekPubSha256(), completion.vaultCert(), e,
                completion.scopeDeviceId(), now);
        enrollments.save(e);
        recordBinding(enrollmentId, completion, deviceId, now);
    }

    private void recordBinding(UUID enrollmentId, TpmCompletion completion, UUID deviceId, Instant now) {
        if (deviceId == null) {
            // Phase 1.5 always resolves a device, so this is a defensive invariant (should be unreachable);
            // fail safe rather than write a (tenant, device)-unkeyed binding the session verifier can't match.
            log.warn("TPM_BINDING_SKIPPED_NO_DEVICE_ID enrollment={} (unexpected post-Phase-1.5)", enrollmentId);
            return;
        }
        // Re-enrollment supersede (Codex): soft-revoke the prior active binding with an IMMEDIATE bulk UPDATE (so
        // it commits before the INSERT, avoiding the Hibernate insert-before-update action-queue ordering tripping
        // the partial unique index), then insert the new active row. uq_tpm_binding_active_device is the last-line
        // defense: a concurrent double-active fails closed (DataIntegrityViolationException), never silent overwrite.
        bindings.revokeActive(completion.tenantId(), deviceId, now, "REENROLLMENT_SUPERSEDED");
        bindings.save(new EndpointTpmDeviceBinding(
                completion.tenantId(), deviceId, enrollmentId, completion.akName(),
                completion.akPubSha256(), completion.ekCertSha256(), completion.deviceKeySpkiSha256(), now, now));
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
     * The inputs the controller hands to {@link #markConsumed} after a successful {@code /attest}. The canonical
     * device is RESOLVED by {@link TpmDeviceCompletionService} (device-less -&gt; created/adopted; pre-bound -&gt;
     * asserted), so {@code scopeDeviceId} is the nullable PRE-BOUND target, not the final device.
     * {@code ekPubSha256}/{@code ekCertSha256} are the L1-bound EK identity digests; {@code vaultCert} is the
     * parsed freshly-issued Vault cert (its SAN is cross-checked against {@code ekPubSha256}); {@code akName} is
     * the RAW TPM Name; AK pub and device-key SPKI are SHA-256 hex digests.
     */
    public record TpmCompletion(UUID tenantId, UUID scopeDeviceId, String ekPubSha256, String ekCertSha256,
                                TpmVaultCertExtractor.ParsedVaultCert vaultCert, byte[] akName,
                                String akPubSha256, String deviceKeySpkiSha256) {
    }
}
