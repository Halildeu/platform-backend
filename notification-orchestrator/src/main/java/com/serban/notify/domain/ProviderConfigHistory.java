package com.serban.notify.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Provider config history — append-only rollback trail (ADR-0013 D30-NOTIFY).
 */
@Entity
@Table(name = "provider_config_history", schema = "notify")
@Immutable
public class ProviderConfigHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_config_id", nullable = false)
    private Long providerConfigId;

    @Column(name = "provider_key", nullable = false, length = 64)
    private String providerKey;

    @Column(nullable = false, length = 16)
    private String environment;

    @Column(nullable = false)
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "credential_ref", length = 255)
    private String credentialRef;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "deactivated_at")
    private OffsetDateTime deactivatedAt;

    @Column(name = "deactivation_reason", columnDefinition = "text")
    private String deactivationReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProviderConfigId() { return providerConfigId; }
    public void setProviderConfigId(Long providerConfigId) { this.providerConfigId = providerConfigId; }
    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(String credentialRef) { this.credentialRef = credentialRef; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(OffsetDateTime activatedAt) { this.activatedAt = activatedAt; }
    public OffsetDateTime getDeactivatedAt() { return deactivatedAt; }
    public void setDeactivatedAt(OffsetDateTime deactivatedAt) { this.deactivatedAt = deactivatedAt; }
    public String getDeactivationReason() { return deactivationReason; }
    public void setDeactivationReason(String deactivationReason) { this.deactivationReason = deactivationReason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderConfigHistory that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode();
    }
}
