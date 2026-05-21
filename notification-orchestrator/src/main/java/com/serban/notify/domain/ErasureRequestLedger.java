package com.serban.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * KVKK Madde 13.2 erasure request ledger (Faz 23.2 M3 R2 PR-K1 —
 * Codex {@code 019e4950} P0 #1 absorb).
 *
 * <p>Hukuki dayanak: KVKK Madde 13.2 — "Veri sorumlusu, başvuruyu
 * talebin niteliğine göre en kısa sürede ve en geç <strong>otuz gün
 * içinde</strong> ücretsiz olarak sonuçlandırır."
 *
 * <p>Append-only saklanır; 90-gün retention purge buna dokunmaz (KVKK
 * denetim sorumluluğu).
 *
 * <p>V18 migration: {@code notify.erasure_request_ledger}.
 */
@Entity
@Table(schema = "notify", name = "erasure_request_ledger")
public class ErasureRequestLedger {

    /**
     * KVKK Madde 13.2 SLA — 30 gün.
     */
    public static final Duration SLA_DURATION = Duration.ofDays(30L);

    public enum RequestSource {
        SELF_SERVICE,
        ADMIN,
        LEGAL,
        DPO,
        COMPLIANCE_AUDIT
    }

    public enum Status {
        RECEIVED,
        PROCESSING,
        COMPLETED,
        LEGAL_HOLD,
        FAILED
    }

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    /**
     * HMAC-SHA256 with org-namespaced Vault pepper (PiiRedactor).
     * Pseudonymous; raw email/phone YASAK (KVKK Madde 12).
     */
    @Column(name = "subject_ref_hmac", nullable = false, length = 128)
    private String subjectRefHmac;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_source", nullable = false, length = 32)
    private RequestSource requestSource;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    /**
     * KVKK Madde 13.2 SLA: {@code received_at + 30 gün}.
     * ErasureSlaWatchdog scheduled scan {@code due_at <= NOW()}
     * → Slack alert.
     */
    @Column(name = "due_at", nullable = false)
    private OffsetDateTime dueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /**
     * KVKK Madde 28 istisna (mahkeme kararı, aktif soruşturma vb.).
     * Sadece DPO/legal erişimi (RBAC ayrı).
     */
    @Column(name = "legal_hold_reason", length = 256)
    private String legalHoldReason;

    /**
     * Cross-request deduplication. Aynı {@code (org_id, idempotency_key)}
     * ikinci başvuru ledger insert UNIQUE violation → service-side
     * no-op (mevcut row döner).
     */
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /**
     * Bağlı {@code audit_event_v2} row referansı (audit chain integrity).
     * COMPLETED durumunda SUBSCRIBER_ERASURE_REQUEST /
     * SUBSCRIBER_SELF_ERASURE_REQUEST event_id.
     */
    @Column(name = "last_audit_event_id")
    private UUID lastAuditEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (requestId == null) {
            requestId = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (receivedAt == null) {
            receivedAt = now;
        }
        if (dueAt == null) {
            dueAt = receivedAt.plus(SLA_DURATION);
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = Status.RECEIVED;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // Getters / Setters

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getSubjectRefHmac() { return subjectRefHmac; }
    public void setSubjectRefHmac(String subjectRefHmac) { this.subjectRefHmac = subjectRefHmac; }

    public RequestSource getRequestSource() { return requestSource; }
    public void setRequestSource(RequestSource requestSource) { this.requestSource = requestSource; }

    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }

    public OffsetDateTime getDueAt() { return dueAt; }
    public void setDueAt(OffsetDateTime dueAt) { this.dueAt = dueAt; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public OffsetDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(OffsetDateTime closedAt) { this.closedAt = closedAt; }

    public String getLegalHoldReason() { return legalHoldReason; }
    public void setLegalHoldReason(String legalHoldReason) { this.legalHoldReason = legalHoldReason; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public UUID getLastAuditEventId() { return lastAuditEventId; }
    public void setLastAuditEventId(UUID lastAuditEventId) { this.lastAuditEventId = lastAuditEventId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    /**
     * KVKK Madde 13.2 SLA breach check (operasyonel).
     *
     * @return true if {@code now > dueAt} and status not terminal
     */
    public boolean isSlaBreached(OffsetDateTime now) {
        if (status == Status.COMPLETED || status == Status.FAILED) {
            return false;
        }
        return now.isAfter(dueAt);
    }
}
