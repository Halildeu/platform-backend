package com.example.permission.dataaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * Faz 21.3 PR-G — JPA mapping for {@code data_access.scope_outbox} (V22).
 *
 * <p>Codex 019dcf5c iter-2 strategic primary: a row here is the durable
 * intent record for an OpenFGA tuple write that must follow a successful
 * {@code data_access.scope} change. The poller ({@link OutboxPoller})
 * claims rows asynchronously, performs the FGA call, and marks
 * {@link Status#PROCESSED} or schedules a retry on failure.
 *
 * <p>Status lifecycle (V22 CHECK):
 * {@code PENDING → PROCESSING → PROCESSED|FAILED}.
 *
 * <p>Action enum (V22 CHECK): {@link Action#GRANT}, {@link Action#REVOKE}.
 * DB stores both action and status as TEXT in upper-case so JPA's
 * {@code EnumType.STRING} maps directly without a converter — V22 picked
 * TEXT-CHECK (rather than PG ENUM TYPE) precisely so future status values
 * can be added with a CHECK rebuild instead of an ALTER TYPE.
 */
@Entity
@Table(name = "scope_outbox", schema = "data_access")
public class DataAccessScopeOutboxEntry {

    public enum Action { GRANT, REVOKE }

    public enum Status { PENDING, PROCESSING, PROCESSED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private Action action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public DataAccessScopeOutboxEntry() {}

    public void markProcessed() {
        this.status = Status.PROCESSED;
        this.processedAt = Instant.now();
        this.lockedBy = null;
        this.lockedUntil = null;
        this.lastError = null;
    }

    public void markFailedTerminal(String error) {
        this.status = Status.FAILED;
        this.lastError = truncate(error);
        this.lockedBy = null;
        this.lockedUntil = null;
        this.processedAt = Instant.now();
    }

    public void scheduleRetry(String error, Instant nextRetry) {
        this.status = Status.PENDING;
        this.nextAttemptAt = nextRetry;
        this.lastError = truncate(error);
        this.lockedBy = null;
        this.lockedUntil = null;
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() > 1024 ? error.substring(0, 1024) : error;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }

    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new HashMap<>() : payload;
    }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }

    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataAccessScopeOutboxEntry that)) return false;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
