package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointTpmDeviceBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.6 #548 slice-1 step-4 — the persisted TPM enrollment binding (see {@link EndpointTpmDeviceBinding}).
 *
 * <p>The single ACTIVE row per {@code (tenantId, deviceId)} is the DB invariant (partial unique index
 * {@code uq_tpm_binding_active_device}); {@link #findByTenantIdAndDeviceIdAndRevokedAtIsNull} is therefore the
 * canonical lookup for both the re-enrollment supersede (revoke-prior) and the later session verifier's match.
 */
@Repository
public interface EndpointTpmDeviceBindingRepository extends JpaRepository<EndpointTpmDeviceBinding, UUID> {

    /** The single active (un-revoked) binding for the device, or empty. */
    Optional<EndpointTpmDeviceBinding> findByTenantIdAndDeviceIdAndRevokedAtIsNull(UUID tenantId, UUID deviceId);

    Optional<EndpointTpmDeviceBinding> findByEndpointEnrollmentId(UUID endpointEnrollmentId);

    /**
     * Soft-revoke the active binding for {@code (tenantId, deviceId)} as part of a re-enrollment supersede. An
     * IMMEDIATE bulk UPDATE (not the entity action queue) so it commits to the DB before the replacement INSERT
     * — otherwise Hibernate's insert-before-update ordering could momentarily leave two active rows and trip the
     * partial unique index. Sets {@code revoked_at} + {@code revoked_reason} together (the CHECK constraint).
     *
     * @return the number of rows revoked (0 or 1, given the single-active invariant)
     */
    @Modifying
    @Query("UPDATE EndpointTpmDeviceBinding b SET b.revokedAt = :now, b.revokedReason = :reason "
            + "WHERE b.tenantId = :tenantId AND b.deviceId = :deviceId AND b.revokedAt IS NULL")
    int revokeActive(@Param("tenantId") UUID tenantId, @Param("deviceId") UUID deviceId,
                     @Param("now") Instant now, @Param("reason") String reason);
}
