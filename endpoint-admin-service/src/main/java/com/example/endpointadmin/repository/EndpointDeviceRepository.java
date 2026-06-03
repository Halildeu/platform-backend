package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Endpoint device repository.
 *
 * <p>Faz 21.1 PR2b-iv.b1 — device-by-id ownership gate migrated from
 * derived {@code findByTenantIdAndId} to explicit {@code @Query} with
 * the canonical effective-org filter (Codex 019e8d1d B-B sub-slice
 * AGREE; P1 parenthesized OR pattern). Accepts both canonical rows
 * (post-PR2b-ii: {@code org_id = tenant_id}) and legacy rows
 * ({@code org_id IS NULL AND tenant_id = :orgId}, defensive — V29
 * trigger normally back-fills but the OR branch guarantees correctness
 * independent of the trigger).
 *
 * <p>The {@code orgId} parameter is the caller's canonical tenant scope
 * (= legacy {@code tenantId}); V30 CHECK guarantees a written row's
 * {@code org_id} matches its {@code tenant_id} when both are populated.
 *
 * <p>Hostname / machineFingerprint / hostnameAsc / statusIn methods stay
 * on the derived form pending PR2b-iv.b2 / b3 / b4 sub-slices.
 */
public interface EndpointDeviceRepository extends JpaRepository<EndpointDevice, UUID> {

    /**
     * Canonical PR2b-iv.b1 read — effective-org device-by-id ownership
     * gate. Accepts both canonical (org_id = tenant_id) and legacy
     * (org_id IS NULL AND tenant_id = :orgId) rows via parenthesized OR.
     * Replaces the pre-PR2b-iv {@code findByTenantIdAndId} derived
     * method. Empty Optional → admin action 404 (no existence leak).
     */
    @Query("""
            select d
            from EndpointDevice d
            where (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
              and d.id = :id
            """)
    Optional<EndpointDevice> findVisibleToOrgAndId(
            @Param("orgId") UUID orgId, @Param("id") UUID id);

    Optional<EndpointDevice> findByTenantIdAndHostname(UUID tenantId, String hostname);

    Optional<EndpointDevice> findByTenantIdAndMachineFingerprint(UUID tenantId, String machineFingerprint);

    List<EndpointDevice> findByTenantIdAndStatusIn(UUID tenantId, Collection<DeviceStatus> statuses);

    List<EndpointDevice> findByTenantIdOrderByHostnameAsc(UUID tenantId);
}
