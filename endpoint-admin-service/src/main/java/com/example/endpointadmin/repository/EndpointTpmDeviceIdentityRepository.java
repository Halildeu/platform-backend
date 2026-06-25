package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointTpmDeviceIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.6 #548 Phase 1.5 — the canonical TPM device-identity lookup (V76,
 * Codex 019eff93 P0-4). Tenant-scoped by construction: the unique key is
 * {@code (tenant_id, ek_pub_sha256)}, so the same physical TPM in two tenants
 * resolves to two independent device rows.
 */
@Repository
public interface EndpointTpmDeviceIdentityRepository extends JpaRepository<EndpointTpmDeviceIdentity, UUID> {

    /** The canonical device for a tenant-scoped EK identity, or empty (→ create a new device). */
    Optional<EndpointTpmDeviceIdentity> findByTenantIdAndEkPubSha256(UUID tenantId, String ekPubSha256);
}
