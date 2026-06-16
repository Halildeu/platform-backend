package com.example.endpointadmin.model;

import com.example.endpointadmin.domainops.DomainOpsOperation;
import com.example.endpointadmin.domainops.DomainOpsStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "endpoint_domain_ops_requests",
        indexes = {
                @Index(name = "idx_endpoint_domain_ops_tenant_state",
                        columnList = "tenant_id,state"),
                @Index(name = "idx_endpoint_domain_ops_device_requested",
                        columnList = "tenant_id,device_id,requested_at DESC"),
                @Index(name = "idx_endpoint_domain_ops_expires",
                        columnList = "expires_at")
        })
public class EndpointDomainOpsRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 64)
    private DomainOpsOperation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private DomainOpsStatus state;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "reason_code", length = 128)
    private String reasonCode;

    @Column(name = "idempotency_key_hash", length = 64)
    private String idempotencyKeyHash;

    @Column(name = "credential_ref", length = 256)
    private String credentialRef;

    @Column(name = "credential_ref_hash", length = 64)
    private String credentialRefHash;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Column(name = "ttl_seconds", nullable = false)
    private Long ttlSeconds;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "state_updated_at", nullable = false)
    private Instant stateUpdatedAt;

    @Column(name = "connector_name", length = 128)
    private String connectorName;

    @Column(name = "connector_attempt_id", length = 128)
    private String connectorAttemptId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_result", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> redactedResult = new HashMap<>();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (requestedAt == null) {
            requestedAt = now;
        }
        if (stateUpdatedAt == null) {
            stateUpdatedAt = requestedAt;
        }
        if (state == null) {
            state = DomainOpsStatus.ACCEPTED;
        }
        if (redactedResult == null) {
            redactedResult = new HashMap<>();
        }
        canonicalizeOrgId();
    }

    @PreUpdate
    void preUpdate() {
        if (stateUpdatedAt == null) {
            stateUpdatedAt = Instant.now();
        }
        if (redactedResult == null) {
            redactedResult = new HashMap<>();
        }
        canonicalizeOrgId();
    }

    private void canonicalizeOrgId() {
        if (orgId == null && tenantId != null) {
            orgId = tenantId;
        }
    }

    public UUID getEffectiveOrgId() {
        return orgId == null ? tenantId : orgId;
    }

    public void markDenied(String reasonCode, Instant completedAt) {
        this.state = DomainOpsStatus.DENIED;
        this.reasonCode = reasonCode;
        this.completedAt = completedAt;
        this.stateUpdatedAt = completedAt;
    }

    public void markConnectorResult(DomainOpsStatus status,
                                    String reasonCode,
                                    String connectorName,
                                    String connectorAttemptId,
                                    Map<String, Object> redactedResult,
                                    Instant transitionAt) {
        this.state = status;
        this.reasonCode = reasonCode;
        this.connectorName = connectorName;
        this.connectorAttemptId = connectorAttemptId;
        this.redactedResult = redactedResult == null ? new HashMap<>() : new HashMap<>(redactedResult);
        if (status == DomainOpsStatus.DISPATCHED) {
            this.dispatchedAt = transitionAt;
        } else if (status == DomainOpsStatus.SUCCEEDED
                || status == DomainOpsStatus.FAILED
                || status == DomainOpsStatus.EXPIRED
                || status == DomainOpsStatus.DENIED) {
            this.completedAt = transitionAt;
        }
        this.stateUpdatedAt = transitionAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EndpointDomainOpsRequest that = (EndpointDomainOpsRequest) o;
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
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public DomainOpsOperation getOperation() { return operation; }
    public void setOperation(DomainOpsOperation operation) { this.operation = operation; }
    public DomainOpsStatus getState() { return state; }
    public void setState(DomainOpsStatus state) { this.state = state; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public void setIdempotencyKeyHash(String idempotencyKeyHash) { this.idempotencyKeyHash = idempotencyKeyHash; }
    public String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(String credentialRef) { this.credentialRef = credentialRef; }
    public String getCredentialRefHash() { return credentialRefHash; }
    public void setCredentialRefHash(String credentialRefHash) { this.credentialRefHash = credentialRefHash; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public Long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(Instant dispatchedAt) { this.dispatchedAt = dispatchedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getStateUpdatedAt() { return stateUpdatedAt; }
    public void setStateUpdatedAt(Instant stateUpdatedAt) { this.stateUpdatedAt = stateUpdatedAt; }
    public String getConnectorName() { return connectorName; }
    public void setConnectorName(String connectorName) { this.connectorName = connectorName; }
    public String getConnectorAttemptId() { return connectorAttemptId; }
    public void setConnectorAttemptId(String connectorAttemptId) { this.connectorAttemptId = connectorAttemptId; }
    public Map<String, Object> getRedactedResult() { return redactedResult; }
    public void setRedactedResult(Map<String, Object> redactedResult) {
        this.redactedResult = redactedResult == null ? new HashMap<>() : new HashMap<>(redactedResult);
    }
    public Long getVersion() { return version; }
}
