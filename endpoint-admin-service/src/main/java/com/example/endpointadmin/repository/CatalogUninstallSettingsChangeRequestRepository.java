package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.CatalogUninstallSettingsChangeRequest;
import com.example.endpointadmin.model.CatalogUninstallSettingsChangeRequestState;
import com.example.endpointadmin.model.CatalogUninstallSettingsField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AG-028 Phase 0 — Spring Data JPA repository for
 * {@link CatalogUninstallSettingsChangeRequest}.
 *
 * <p>All finders are tenant-scoped. The partial unique index
 * {@code uq_catalog_unins_change_one_open} guarantees at most one open
 * request per (tenant, catalog_item, field) regardless of in-memory race;
 * second concurrent propose hits a DB constraint violation that the
 * service layer maps to a 409 CONFLICT response.
 */
public interface CatalogUninstallSettingsChangeRequestRepository
        extends JpaRepository<CatalogUninstallSettingsChangeRequest, UUID> {

    Optional<CatalogUninstallSettingsChangeRequest>
        findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Find an open (PROPOSED or APPROVED) request for the given catalog
     * item + field. Used by the service layer to enforce the open-request
     * uniqueness at the read path before relying on the DB constraint.
     */
    @Query("""
            SELECT r FROM CatalogUninstallSettingsChangeRequest r
            WHERE r.tenantId = :tenantId
              AND r.catalogItemId = :catalogItemId
              AND r.field = :field
              AND r.state IN :states
            """)
    Optional<CatalogUninstallSettingsChangeRequest> findOpenForCatalogItemAndField(
            @Param("tenantId") UUID tenantId,
            @Param("catalogItemId") UUID catalogItemId,
            @Param("field") CatalogUninstallSettingsField field,
            @Param("states") List<CatalogUninstallSettingsChangeRequestState> states);

    /**
     * Convenience wrapper using the canonical open-state set
     * {@code (PROPOSED, APPROVED)}. Service layer uses this entry point.
     */
    default Optional<CatalogUninstallSettingsChangeRequest> findOpenForCatalogItemAndField(
            UUID tenantId, UUID catalogItemId, CatalogUninstallSettingsField field) {
        return findOpenForCatalogItemAndField(tenantId, catalogItemId, field,
                List.of(CatalogUninstallSettingsChangeRequestState.PROPOSED,
                        CatalogUninstallSettingsChangeRequestState.APPROVED));
    }

    List<CatalogUninstallSettingsChangeRequest>
        findByTenantIdAndState(UUID tenantId,
                               CatalogUninstallSettingsChangeRequestState state);

    /**
     * History view: all requests for a catalog item ordered by proposed_at desc.
     * Used for the catalog drawer "Yönetim Hakları" pending/history panel.
     */
    List<CatalogUninstallSettingsChangeRequest>
        findByTenantIdAndCatalogItemIdOrderByProposedAtDesc(
                UUID tenantId, UUID catalogItemId);
}
