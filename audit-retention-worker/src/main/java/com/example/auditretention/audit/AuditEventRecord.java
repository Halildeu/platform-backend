package com.example.auditretention.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — read-only projection of one
 * {@code audit_event.audit_event} row.
 *
 * <p>Plain POJO (NOT a JPA entity): the worker reads rows with {@code
 * JdbcTemplate}, re-hashes them to verify the PER_TENANT chain, then serializes
 * them to NDJSON. The getter set is intentionally <b>identical</b> to the
 * consumer's {@code com.example.auditconsumer.model.AuditEvent}
 * (provenance {@code platform-backend@74c9e1a9}) so the verbatim-copied {@link
 * AuditChainSupport#canonicalPayload} produces a byte-identical payload and the
 * re-hash reproduces the stored {@code entry_hash}. Carries the full column set
 * (including {@code seq}, {@code ingested_at}, {@code stream_entry_id}, and the
 * chain columns) for NDJSON emission — but only the canonical subset feeds the
 * hash (see {@code AuditChainSupport}).
 */
public class AuditEventRecord {

    private Long seq;
    private UUID id;
    private Long tenantId;
    private String eventType;
    private String sessionId;
    private Long userId;
    private Long chunkSeq;
    private Integer httpStatus;
    private String rejectionCode;
    private Long retryAfterSeconds;
    private String correlationId;
    private Instant eventTimestamp;
    private Instant ingestedAt;
    private String dedupKey;
    private String streamEntryId;
    private String prevHash;
    private String entryHash;
    private String entryHashAlg;
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

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
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

    public void setIngestedAt(Instant ingestedAt) {
        this.ingestedAt = ingestedAt;
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
}
