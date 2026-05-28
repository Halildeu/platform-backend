package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointInstallAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * BE-021 — Spring Data JPA repository for {@link EndpointInstallAudit}.
 *
 * <p>All finders are tenant-scoped; callers always pass the
 * {@code tenantId} from the resolved admin context. The compliance
 * evaluator deterministic-selector query
 * {@link #findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(UUID, UUID, UUID, Instant)}
 * uses {@code created_at < before} (database commit timestamp) so the
 * selector cannot observe its own evaluation's audit row. The matching
 * partial index {@code idx_endpoint_install_audit_eval_selector} (V12)
 * keeps this read on the hot path.
 */
public interface EndpointInstallAuditRepository extends JpaRepository<EndpointInstallAudit, UUID> {

    Optional<EndpointInstallAudit> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<EndpointInstallAudit> findByCommandId(UUID commandId);

    Page<EndpointInstallAudit> findByTenantIdAndDeviceIdOrderByReportedAtDesc(
            UUID tenantId, UUID deviceId, Pageable pageable);

    Page<EndpointInstallAudit> findByTenantIdOrderByReportedAtDesc(
            UUID tenantId, Pageable pageable);

    @Query("""
            select audit
            from EndpointInstallAudit audit
            where audit.tenantId = :tenantId
              and audit.deviceId = :deviceId
              and audit.catalogItemId = :catalogItemId
              and audit.resultStatus = com.example.endpointadmin.model.CommandResultStatus.SUCCEEDED
              and audit.postVerification = com.example.endpointadmin.model.InstallPostVerification.SATISFIED
              and audit.createdAt < :before
            order by audit.createdAt desc
            """)
    java.util.List<EndpointInstallAudit>
        findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                @Param("tenantId") UUID tenantId,
                @Param("deviceId") UUID deviceId,
                @Param("catalogItemId") UUID catalogItemId,
                @Param("before") Instant before,
                Pageable pageable);

    default Optional<EndpointInstallAudit>
        findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                UUID tenantId, UUID deviceId, UUID catalogItemId, Instant before) {
        var page = findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                tenantId, deviceId, catalogItemId, before,
                org.springframework.data.domain.PageRequest.of(0, 1));
        return page.isEmpty() ? Optional.empty() : Optional.of(page.get(0));
    }
}
