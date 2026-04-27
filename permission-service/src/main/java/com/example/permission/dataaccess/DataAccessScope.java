package com.example.permission.dataaccess;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA mapping for {@code data_access.scope} (reports_db, V19/V20 immutable migrations).
 *
 * <p>Owned by Faz 21.3 ADR-0008 explicit-scope contract: a row here represents an
 * admin-issued data-access grant. The PG schema lives in the {@code reports_db}
 * (lineage-locality, see ADR-0005); this entity is bound to the secondary
 * {@code reportsDbEntityManagerFactory}.
 *
 * <p>Naming bridge (PG vs OpenFGA): {@code scope_kind = 'depot'} maps to the
 * OpenFGA object type {@code warehouse} via {@link DataAccessScopeTupleEncoder}.
 */
@Entity
@Table(name = "scope", schema = "data_access")
public class DataAccessScope {

    public enum ScopeKind {
        COMPANY, PROJECT, DEPOT, BRANCH;

        public String dbValue() {
            return name().toLowerCase();
        }

        public static ScopeKind fromDbValue(String raw) {
            if (raw == null) return null;
            return ScopeKind.valueOf(raw.toUpperCase());
        }
    }

    @Converter(autoApply = false)
    public static class ScopeKindConverter implements AttributeConverter<ScopeKind, String> {
        @Override
        public String convertToDatabaseColumn(ScopeKind attribute) {
            return attribute == null ? null : attribute.dbValue();
        }

        @Override
        public ScopeKind convertToEntityAttribute(String dbData) {
            return ScopeKind.fromDbValue(dbData);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Convert(converter = ScopeKindConverter.class)
    @Column(name = "scope_kind", nullable = false)
    private ScopeKind scopeKind;

    @Column(name = "scope_source_schema", nullable = false)
    private String scopeSourceSchema = "workcube_mikrolink";

    @Column(name = "scope_source_table", nullable = false)
    private String scopeSourceTable;

    @Column(name = "scope_ref", nullable = false)
    private String scopeRef;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by", columnDefinition = "uuid")
    private UUID grantedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", columnDefinition = "uuid")
    private UUID revokedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> notes = new HashMap<>();

    public DataAccessScope() {}

    public boolean isActive() {
        return revokedAt == null;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public Long getOrgId() { return orgId; }
    public void setOrgId(Long orgId) { this.orgId = orgId; }

    public ScopeKind getScopeKind() { return scopeKind; }
    public void setScopeKind(ScopeKind scopeKind) { this.scopeKind = scopeKind; }

    public String getScopeSourceSchema() { return scopeSourceSchema; }
    public void setScopeSourceSchema(String scopeSourceSchema) { this.scopeSourceSchema = scopeSourceSchema; }

    public String getScopeSourceTable() { return scopeSourceTable; }
    public void setScopeSourceTable(String scopeSourceTable) { this.scopeSourceTable = scopeSourceTable; }

    public String getScopeRef() { return scopeRef; }
    public void setScopeRef(String scopeRef) { this.scopeRef = scopeRef; }

    public Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Instant grantedAt) { this.grantedAt = grantedAt; }

    public UUID getGrantedBy() { return grantedBy; }
    public void setGrantedBy(UUID grantedBy) { this.grantedBy = grantedBy; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public UUID getRevokedBy() { return revokedBy; }
    public void setRevokedBy(UUID revokedBy) { this.revokedBy = revokedBy; }

    public Map<String, Object> getNotes() { return notes; }
    public void setNotes(Map<String, Object> notes) { this.notes = notes == null ? new HashMap<>() : notes; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataAccessScope that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
