package com.example.auditconsumer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — immutable audit event row.
 *
 * <p>Insert-only. The {@link Immutable} mapping aligns Hibernate with the
 * DB-level append-only trigger ({@code trg_audit_event_append_only}): an audit
 * row is INSERTed once and never dirty-checked / UPDATEd again (BE-016
 * {@code EndpointAuditEvent} pattern reuse — without {@code @Immutable} a later
 * auto-flush in the same transaction could schedule a spurious UPDATE that the
 * trigger rejects with a 500).
 *
 * <p>The {@code seq} BIGSERIAL is DB-assigned and is the per-tenant hash-chain
 * ordering anchor + global monotonic ingest order. The application sets every
 * other column (including the chain columns) before INSERT.
 */
@Entity
@Immutable
@Table(name = "audit_event",
        indexes = {
                @Index(name = "idx_audit_event_tenant_seq", columnList = "tenant_id,seq DESC"),
                @Index(name = "idx_audit_event_event_timestamp", columnList = "event_timestamp"),
                @Index(name = "idx_audit_event_org_id", columnList = "org_id"),
                @Index(name = "idx_audit_event_event_type", columnList = "event_type")
        })
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq", updatable = false)
    private Long seq;

    @Column(name = "id", nullable = false, unique = true, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "session_id", length = 128, updatable = false)
    private String sessionId;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "chunk_seq", updatable = false)
    private Long chunkSeq;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "rejection_code", length = 100, updatable = false)
    private String rejectionCode;

    @Column(name = "retry_after_seconds", updatable = false)
    private Long retryAfterSeconds;

    @Column(name = "correlation_id", length = 128, updatable = false)
    private String correlationId;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(name = "ingested_at", updatable = false, insertable = false)
    private Instant ingestedAt;

    @Column(name = "dedup_key", nullable = false, unique = true, length = 320, updatable = false)
    private String dedupKey;

    @Column(name = "stream_entry_id", length = 64, updatable = false)
    private String streamEntryId;

    @Column(name = "prev_hash", length = 64, updatable = false)
    private String prevHash;

    @Column(name = "entry_hash", nullable = false, length = 64, updatable = false)
    private String entryHash;

    @Column(name = "entry_hash_alg", nullable = false, length = 32, updatable = false)
    private String entryHashAlg;

    @Column(name = "entry_hash_version", nullable = false, updatable = false)
    private Integer entryHashVersion;

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getChunkSeq() {
        return chunkSeq;
    }

    public void setChunkSeq(Long chunkSeq) {
        this.chunkSeq = chunkSeq;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getRejectionCode() {
        return rejectionCode;
    }

    public void setRejectionCode(String rejectionCode) {
        this.rejectionCode = rejectionCode;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public void setRetryAfterSeconds(Long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Instant eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getStreamEntryId() {
        return streamEntryId;
    }

    public void setStreamEntryId(String streamEntryId) {
        this.streamEntryId = streamEntryId;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getEntryHash() {
        return entryHash;
    }

    public void setEntryHash(String entryHash) {
        this.entryHash = entryHash;
    }

    public String getEntryHashAlg() {
        return entryHashAlg;
    }

    public void setEntryHashAlg(String entryHashAlg) {
        this.entryHashAlg = entryHashAlg;
    }

    public Integer getEntryHashVersion() {
        return entryHashVersion;
    }

    public void setEntryHashVersion(Integer entryHashVersion) {
        this.entryHashVersion = entryHashVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AuditEvent that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
