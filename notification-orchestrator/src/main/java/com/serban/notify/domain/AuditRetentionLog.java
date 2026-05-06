package com.serban.notify.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * Audit retention log — two-phase partition retention metadata
 * (Faz 23.2 PR-D.1 — Codex 019dfdec Q5 absorb).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link Status#detached}: AuditPartitionRetentionService.detach phase
 *       runs DETACH PARTITION + INSERT row with detached_at = now,
 *       drop_after = now + grace_hours.</li>
 *   <li>{@link Status#dropped}: drop phase (next scheduled run after grace)
 *       runs DROP TABLE + UPDATE row with dropped_at = now, status = dropped.</li>
 *   <li>{@link Status#failed}: error path — error_message captured.</li>
 * </ol>
 *
 * <p>Operatör log'tan dropped partition geçmişini izler. {@code dropped_at IS NULL}
 * → henüz DROP edilmemiş (grace pencerede veya dry_run).
 */
@Entity
@Table(name = "audit_retention_log", schema = "notify")
public class AuditRetentionLog {

    public enum Status { detached, dropped, failed }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "partition_name", nullable = false, unique = true, length = 128)
    private String partitionName;

    @Column(name = "range_start", nullable = false)
    private OffsetDateTime rangeStart;

    @Column(name = "range_end", nullable = false)
    private OffsetDateTime rangeEnd;

    @Column(name = "detached_at", nullable = false)
    private OffsetDateTime detachedAt;

    @Column(name = "drop_after", nullable = false)
    private OffsetDateTime dropAfter;

    @Column(name = "dropped_at")
    private OffsetDateTime droppedAt;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status = Status.detached;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (detachedAt == null) detachedAt = now;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPartitionName() { return partitionName; }
    public void setPartitionName(String partitionName) { this.partitionName = partitionName; }
    public OffsetDateTime getRangeStart() { return rangeStart; }
    public void setRangeStart(OffsetDateTime rangeStart) { this.rangeStart = rangeStart; }
    public OffsetDateTime getRangeEnd() { return rangeEnd; }
    public void setRangeEnd(OffsetDateTime rangeEnd) { this.rangeEnd = rangeEnd; }
    public OffsetDateTime getDetachedAt() { return detachedAt; }
    public void setDetachedAt(OffsetDateTime detachedAt) { this.detachedAt = detachedAt; }
    public OffsetDateTime getDropAfter() { return dropAfter; }
    public void setDropAfter(OffsetDateTime dropAfter) { this.dropAfter = dropAfter; }
    public OffsetDateTime getDroppedAt() { return droppedAt; }
    public void setDroppedAt(OffsetDateTime droppedAt) { this.droppedAt = droppedAt; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuditRetentionLog that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }
}
