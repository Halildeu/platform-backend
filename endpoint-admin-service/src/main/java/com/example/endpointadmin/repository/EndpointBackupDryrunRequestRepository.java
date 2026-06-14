package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.BackupDryrunRequestState;
import com.example.endpointadmin.model.EndpointBackupDryrunRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.8A.3b (#648) — backup dry-run issuing request repository. Mirrors the
 * uninstall request repo: idempotency lookup, a PESSIMISTIC_WRITE row lock for
 * the approve maker-checker serialisation, and a single-flight open-request
 * read. All tenant-scoped.
 */
public interface EndpointBackupDryrunRequestRepository
        extends JpaRepository<EndpointBackupDryrunRequest, UUID> {

    Optional<EndpointBackupDryrunRequest> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    /**
     * PESSIMISTIC_WRITE lock on the request row — serialises concurrent
     * approvers (the second observes the state already past PENDING_APPROVAL and
     * gets a clean 409).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from EndpointBackupDryrunRequest r
            where r.tenantId = :tenantId and r.id = :id
            """)
    Optional<EndpointBackupDryrunRequest> findByTenantIdAndIdForUpdate(
            @Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** Soft single-flight read for a clear operator 409 (DB partial unique is the hard guard). */
    @Query("""
            select r from EndpointBackupDryrunRequest r
            where r.tenantId = :tenantId and r.deviceId = :deviceId
              and r.state = :state
            """)
    Optional<EndpointBackupDryrunRequest> findOpenForDevice(
            @Param("tenantId") UUID tenantId, @Param("deviceId") UUID deviceId,
            @Param("state") BackupDryrunRequestState state);

    List<EndpointBackupDryrunRequest> findByTenantIdAndDeviceIdOrderByCreatedAtDesc(
            UUID tenantId, UUID deviceId, Pageable pageable);

    Optional<EndpointBackupDryrunRequest> findByTenantIdAndId(UUID tenantId, UUID id);
}
