package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointBackupDryrunManagedRoot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.8A.3a (#648) — managed-data-root registry repository. All lookups are
 * tenant-scoped (fail-closed: never resolve a root_ref across tenants).
 */
public interface EndpointBackupDryrunManagedRootRepository
        extends JpaRepository<EndpointBackupDryrunManagedRoot, UUID> {

    Optional<EndpointBackupDryrunManagedRoot> findByTenantIdAndRootRef(UUID tenantId, String rootRef);

    Optional<EndpointBackupDryrunManagedRoot> findByIdAndTenantId(UUID id, UUID tenantId);

    List<EndpointBackupDryrunManagedRoot> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    /**
     * Resolve a set of root_refs to their registry entries for the issuing
     * surface (22.8A.3b). Only ENABLED + COMPANY-MANAGED roots resolve; a
     * missing / disabled / BYOD ref is absent from the result so the caller can
     * fail-closed (Codex 019ec45e round-2 — BYOD durable at the resolver, not
     * just the register path).
     */
    List<EndpointBackupDryrunManagedRoot> findByTenantIdAndEnabledTrueAndCompanyManagedTrueAndRootRefIn(
            UUID tenantId, List<String> rootRefs);
}
