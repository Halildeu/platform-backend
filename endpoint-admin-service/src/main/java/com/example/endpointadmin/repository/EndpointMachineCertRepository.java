package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointMachineCert;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.3 — EndpointMachineCert query API. Active = revoked_at IS NULL
 * (partial unique indexes on (device_id) and (san_uri) enforce single-active).
 */
public interface EndpointMachineCertRepository extends JpaRepository<EndpointMachineCert, UUID> {

    @Query("""
            SELECT c FROM EndpointMachineCert c
            WHERE c.sanUri = :sanUri
              AND c.revokedAt IS NULL
            """)
    Optional<EndpointMachineCert> findActiveBySanUri(@Param("sanUri") String sanUri);

    @Query("""
            SELECT c FROM EndpointMachineCert c
            WHERE c.device.id = :deviceId
              AND c.revokedAt IS NULL
            """)
    Optional<EndpointMachineCert> findActiveByDeviceId(@Param("deviceId") UUID deviceId);

    @Query("""
            SELECT c FROM EndpointMachineCert c
            WHERE c.tenantId = :tenantId
              AND c.machineFingerprint = :machineFingerprint
              AND c.revokedAt IS NULL
            """)
    List<EndpointMachineCert> findActiveByTenantAndMachineFingerprint(
            @Param("tenantId") UUID tenantId,
            @Param("machineFingerprint") String machineFingerprint);

    @Query("""
            SELECT c FROM EndpointMachineCert c
            WHERE c.tenantId = :tenantId
              AND c.certThumbprint = :thumbprint
            """)
    List<EndpointMachineCert> findByTenantIdAndCertThumbprint(
            @Param("tenantId") UUID tenantId,
            @Param("thumbprint") String thumbprint);

    /**
     * Faz 22.6 slice-4c-2b-2a (Codex 019ebe06) — the single ACTIVE cert for a device WITHIN a tenant, with the
     * device eagerly fetched. Tenant-scoped (the boundary lives in the data-access contract, not a post-filter:
     * a cross-tenant device row is never materialized) + {@code revoked_at IS NULL} (the partial unique index
     * keeps it single-active). {@code JOIN FETCH c.device} so the operator-side resolver can read the device
     * status outside an open transaction without a lazy-init failure.
     *
     * <p>BOTH the cert row's tenant AND the joined device row's tenant are pinned to {@code :tenantId} (Codex
     * REVISE): {@code device_id} is a single-column FK, so a corrupt/raced/hand-edited row could pair a
     * tenant-A cert with a tenant-B device; a security boundary must refuse it, so the device tenant is part of
     * the query predicate (the resolver also re-checks it post-fetch, defense-in-depth).
     */
    @Query("""
            SELECT c FROM EndpointMachineCert c
            JOIN FETCH c.device d
            WHERE c.tenantId = :tenantId
              AND d.id = :deviceId
              AND d.tenantId = :tenantId
              AND c.revokedAt IS NULL
            """)
    Optional<EndpointMachineCert> findActiveByTenantIdAndDeviceId(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId);

    /**
     * #527 CERT_IDENTITY autonomous ingest — active certs whose validity window
     * has closed. Device is fetched eagerly so the scanner can build a truthful
     * queue item without touching lazy state outside the read transaction.
     */
    @Query("""
            SELECT c FROM EndpointMachineCert c
            JOIN FETCH c.device d
            WHERE c.revokedAt IS NULL
              AND c.certNotAfter <= :now
              AND d.status <> :excludedStatus
            ORDER BY c.certNotAfter ASC
            """)
    List<EndpointMachineCert> findExpiredActiveCerts(
            @Param("now") Instant now,
            @Param("excludedStatus") DeviceStatus excludedStatus,
            Pageable pageable);

    /**
     * Faz 22.6 #548 Phase 1.5 — tenant-scoped active cert by SAN URI (the VAULT_TPM
     * adoption/idempotency lookup). Tenant-scoped because the TPM channel's active
     * uniqueness is {@code (tenant_id, san_uri)}: the same physical EK in two tenants is
     * two independent devices, never a cross-tenant collision.
     */
    @Query("""
            SELECT c FROM EndpointMachineCert c
            WHERE c.tenantId = :tenantId
              AND c.sanUri = :sanUri
              AND c.revokedAt IS NULL
            """)
    Optional<EndpointMachineCert> findActiveByTenantIdAndSanUri(@Param("tenantId") UUID tenantId,
                                                                @Param("sanUri") String sanUri);

    /**
     * Faz 22.6 #548 Phase 1.5 — revoke the device's ACTIVE cert (ANY channel) as an
     * IMMEDIATE bulk UPDATE before the replacement INSERT, mirroring the TPM-binding
     * supersede (Codex 019eff93 Inv-1 + P1-6). A pre-bound AD_CS device upgrading to
     * TPM-native must free the GLOBAL per-device active slot
     * ({@code uq_endpoint_machine_certs_device_active}) before the VAULT_TPM row inserts,
     * else Hibernate's insert-before-update action ordering trips that partial unique.
     * Sets {@code revoked_at}+{@code revoked_reason} together (the CHECK) + {@code updated_at}
     * (a bulk UPDATE bypasses {@code @PreUpdate}).
     *
     * @return rows revoked (0 or 1, given the per-device single-active invariant)
     */
    @Modifying
    @Query("""
            UPDATE EndpointMachineCert c
               SET c.revokedAt = :now, c.revokedReason = :reason, c.updatedAt = :now
             WHERE c.device.id = :deviceId AND c.revokedAt IS NULL
            """)
    int revokeActiveCertForDevice(@Param("deviceId") UUID deviceId,
                                  @Param("now") Instant now,
                                  @Param("reason") String reason);
}
