package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * AG-028 Phase 0 — JPA entity for the catalog uninstall settings
 * change-request maker-checker flow (Faz 22.5.6).
 *
 * <p>Approved catalog rows are immutable via direct PATCH in the current
 * {@code EndpointSoftwareCatalogService} (DRAFT-only PUT path). Flag
 * flips on APPROVED catalog rows go through the propose/approve cycle
 * carried by this entity so {@code uninstall_supported} and
 * {@code uninstall_protected} mutations honor maker-checker
 * (approver ≠ proposer) just like the initial DRAFT→APPROVED transition.
 *
 * <p>DB invariants enforced in V31:
 * <ul>
 *   <li>{@code ck_catalog_unins_change_field}: closed set of flippable fields.</li>
 *   <li>{@code ck_catalog_unins_change_state}: closed state machine.</li>
 *   <li>{@code ck_catalog_unins_change_maker_checker}: approver ≠ proposer.</li>
 *   <li>{@code ck_catalog_unins_change_approved_pair}: approval pair together.</li>
 *   <li>{@code ck_catalog_unins_change_state_pairing}: per-state field combos.</li>
 *   <li>{@code uq_catalog_unins_change_one_open}: partial unique on PROPOSED/APPROVED.</li>
 *   <li>{@code fk_catalog_unins_change_catalog}: composite {@code (catalog_item_id, tenant_id)} FK.</li>
 * </ul>
 *
 * <p>Cross-AI plan-time Codex consensus thread
 * {@code 019e8c8a-4c90-7c00-8f64-c88d47801a06} iter-6 AGREE.
 */
@Entity
@Table(name = "catalog_uninstall_settings_change_requests",
        indexes = {
                @Index(name = "idx_catalog_unins_change_tenant_state",
                        columnList = "tenant_id,state"),
                @Index(name = "idx_catalog_unins_change_catalog_state",
                        columnList = "catalog_item_id,state")
        })
public class CatalogUninstallSettingsChangeRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "catalog_item_id", nullable = false)
    private UUID catalogItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, length = 32)
    private CatalogUninstallSettingsField field;

    @Column(name = "new_value", nullable = false)
    private boolean newValue;

    @Column(name = "proposed_by", nullable = false, length = 255)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private CatalogUninstallSettingsChangeRequestState state;

    @Column(name = "reject_reason", columnDefinition = "text")
    private String rejectReason;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (proposedAt == null) {
            proposedAt = now;
        }
        if (state == null) {
            state = CatalogUninstallSettingsChangeRequestState.PROPOSED;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CatalogUninstallSettingsChangeRequest that = (CatalogUninstallSettingsChangeRequest) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getCatalogItemId() { return catalogItemId; }
    public void setCatalogItemId(UUID catalogItemId) { this.catalogItemId = catalogItemId; }
    public CatalogUninstallSettingsField getField() { return field; }
    public void setField(CatalogUninstallSettingsField field) { this.field = field; }
    public boolean isNewValue() { return newValue; }
    public void setNewValue(boolean newValue) { this.newValue = newValue; }
    public String getProposedBy() { return proposedBy; }
    public void setProposedBy(String proposedBy) { this.proposedBy = proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public void setProposedAt(Instant proposedAt) { this.proposedAt = proposedAt; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
    public CatalogUninstallSettingsChangeRequestState getState() { return state; }
    public void setState(CatalogUninstallSettingsChangeRequestState state) { this.state = state; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
