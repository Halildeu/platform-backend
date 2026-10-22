package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDisplayPolicyAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * #508 managed wallpaper assets (platform-backend#1203). Reads are tenant-keyed;
 * {@code org_id = tenant_id}, so the V83 {@code (org_id, sha256)} unique index is
 * the arbiter these lookups align with.
 */
public interface EndpointDisplayPolicyAssetRepository
        extends JpaRepository<EndpointDisplayPolicyAsset, UUID> {

    Optional<EndpointDisplayPolicyAsset> findByTenantIdAndSha256(UUID tenantId, String sha256);
}
