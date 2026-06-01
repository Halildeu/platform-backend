package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * BE — child probe-error row for AG-040 startup exposure snapshot.
 * Mirrors AG-039-be probe_errors pattern: single-column @JoinColumn
 * snapshot_id + scalar tenant_id mirrored + DB-layer composite FK.
 *
 * <p>Bounded {@code code} enum (9 codes) + optional {@code source}
 * (autorun-anchor enum, allowlist-only) + bounded optional
 * {@code summary} ≤200 + CR/LF reject enforced at the DB (V25 CHECK
 * constraints, secondary line of defense) and at the
 * {@code StartupExposurePayloadPolicy} layer (strict-allowlist +
 * value-level denylist via SUMMARY_VALUE_DENYLIST_RE reuse, primary).
 */
@Entity
@Table(name = "endpoint_startup_exposure_probe_errors")
public class EndpointStartupExposureProbeError {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointStartupExposureSnapshot snapshot;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "row_ordinal", nullable = false)
    private Integer rowOrdinal;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "source", length = 48)
    private String source;

    @Column(name = "summary", length = 200)
    private String summary;

    @PrePersist
    void onPersist() {
        if (tenantId == null && snapshot != null) {
            tenantId = snapshot.getTenantId();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public EndpointStartupExposureSnapshot getSnapshot() { return snapshot; }
    public void setSnapshot(EndpointStartupExposureSnapshot snapshot) { this.snapshot = snapshot; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public Integer getRowOrdinal() { return rowOrdinal; }
    public void setRowOrdinal(Integer rowOrdinal) { this.rowOrdinal = rowOrdinal; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
