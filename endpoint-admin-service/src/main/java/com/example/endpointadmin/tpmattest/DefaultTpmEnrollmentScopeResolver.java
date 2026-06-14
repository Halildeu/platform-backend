package com.example.endpointadmin.tpmattest;

import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EnrollmentStatus;
import com.example.endpointadmin.repository.EndpointEnrollmentRepository;
import com.example.endpointadmin.security.EnrollmentTokenHasher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Faz 22.3B (ADR-0039) gate-4d — resolves the enrollment scope by validating the bootstrap token
 * <b>read-only</b> against the existing enrollment record (reusing {@link EnrollmentTokenHasher} +
 * {@link EndpointEnrollmentRepository}, the same channel as {@code /agent/enrollments/consume}).
 * It does NOT consume/mutate the enrollment — the token stays pending; the TPM nonce's single-use +
 * the /attest completion (gate-4d-2) handle the lifecycle. Fail-closed on any invalid input.
 */
@Component
public class DefaultTpmEnrollmentScopeResolver implements TpmEnrollmentScopeResolver {

    private final EnrollmentTokenHasher tokenHasher;
    private final EndpointEnrollmentRepository enrollments;
    private final Clock clock;

    public DefaultTpmEnrollmentScopeResolver(EnrollmentTokenHasher tokenHasher,
                                             EndpointEnrollmentRepository enrollments,
                                             Clock clock) {
        this.tokenHasher = tokenHasher;
        this.enrollments = enrollments;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Scope resolve(String enrollmentToken) {
        if (enrollmentToken == null || enrollmentToken.isBlank()) {
            throw deny("enrollment token required");
        }
        String hash;
        try {
            hash = tokenHasher.hash(enrollmentToken);
        } catch (RuntimeException e) {
            throw deny("enrollment token could not be hashed");
        }
        EndpointEnrollment enr = enrollments
                .findByEnrollmentTokenHashAndStatus(hash, EnrollmentStatus.PENDING)
                .orElseThrow(() -> deny("no pending enrollment for the presented token"));
        if (!clock.instant().isBefore(enr.getExpiresAt())) {
            throw deny("enrollment token expired");
        }
        UUID deviceId = enr.getDevice() != null ? enr.getDevice().getId() : null;
        // Server-derived scope: token_id (enrollment id) | tenant | device. Never caller-supplied.
        String nonceScope = "tpm-enroll:" + enr.getId() + "|" + enr.getTenantId()
                + "|" + (deviceId != null ? deviceId : "pending");
        return new Scope(enr.getTenantId(), enr.getId(), deviceId, nonceScope);
    }

    private static TpmAttestException deny(String detail) {
        return new TpmAttestException(TpmDenyCode.DEVICE_NOT_ELIGIBLE, detail);
    }
}
