package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EnrollmentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointEnrollmentRepository extends JpaRepository<EndpointEnrollment, UUID> {

    Optional<EndpointEnrollment> findByEnrollmentTokenHashAndStatus(String enrollmentTokenHash,
                                                                    EnrollmentStatus status);

    Optional<EndpointEnrollment> findByEnrollmentTokenHash(String enrollmentTokenHash);

    List<EndpointEnrollment> findByTenantIdAndStatus(UUID tenantId, EnrollmentStatus status);

    List<EndpointEnrollment> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<EndpointEnrollment> findByStatusAndExpiresAtBefore(EnrollmentStatus status, Instant expiresAt);

    /**
     * #527 CERT_IDENTITY autonomous ingest — only device-bound failures can be
     * represented honestly in the failed-device queue because the contract
     * requires a device_id. Device-less enrollment failures are skipped by
     * design rather than assigned to a fabricated device.
     */
    @Query("""
            SELECT e FROM EndpointEnrollment e
            JOIN FETCH e.device d
            WHERE e.status = :status
              AND d.status <> :excludedStatus
            ORDER BY e.updatedAt DESC
            """)
    List<EndpointEnrollment> findDeviceBoundByStatusExcludingDeviceStatus(
            @Param("status") EnrollmentStatus status,
            @Param("excludedStatus") DeviceStatus excludedStatus,
            Pageable pageable);

    @Modifying
    @Query("""
            UPDATE EndpointEnrollment enrollment
            SET enrollment.status = com.example.endpointadmin.model.EnrollmentStatus.CONSUMED,
                enrollment.device = :device,
                enrollment.consumedAt = :consumedAt
            WHERE enrollment.id = :id
              AND enrollment.status = com.example.endpointadmin.model.EnrollmentStatus.PENDING
              AND enrollment.expiresAt > :consumedAt
            """)
    int markConsumed(@Param("id") UUID id,
                     @Param("device") com.example.endpointadmin.model.EndpointDevice device,
                     @Param("consumedAt") Instant consumedAt);

    @Modifying
    @Query("""
            UPDATE EndpointEnrollment enrollment
            SET enrollment.status = com.example.endpointadmin.model.EnrollmentStatus.EXPIRED
            WHERE enrollment.id = :id
              AND enrollment.status = com.example.endpointadmin.model.EnrollmentStatus.PENDING
              AND enrollment.expiresAt <= :now
            """)
    int markExpired(@Param("id") UUID id, @Param("now") Instant now);
}
