package com.example.meeting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Root meeting aggregate — Faz 24 meeting intelligence (#410).
 *
 * <p>Multi-tenancy follows the endpoint-admin org_id compat pattern: a
 * legacy {@code tenantId} column plus a canonical {@code orgId} column.
 * The canonical write path sets BOTH (V1 trigger also back-fills
 * {@code orgId := tenantId} when a legacy writer leaves it null). Read
 * paths should call {@link #getEffectiveOrgId()} so legacy rows
 * ({@code orgId IS NULL}, {@code tenantId NOT NULL}) still resolve.
 *
 * <p>UUID generation is Hibernate-side
 * ({@link GenerationType#UUID}), not a DB {@code DEFAULT
 * gen_random_uuid()}, matching the endpoint-admin catalog/rule entities.
 */
@Entity
@Table(name = "meetings",
        indexes = {
                @Index(name = "idx_meetings_tenant_status", columnList = "tenant_id,status"),
                @Index(name = "idx_meetings_org_id", columnList = "org_id")
        })
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Canonical org scope. Nullable until a cleanup migration drops
     * {@code tenantId}. Read paths should prefer {@link #getEffectiveOrgId()}.
     */
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MeetingStatus status = MeetingStatus.SCHEDULED;

    @Column(name = "scheduled_start")
    private Instant scheduledStart;

    @Column(name = "scheduled_end")
    private Instant scheduledEnd;

    /**
     * Canonical meeting-history key. It starts as the scheduled start (or
     * creation time) and is replaced by the first attended recording start.
     */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "organizer_subject", nullable = false, length = 255)
    private String organizerSubject;

    @Column(name = "created_by_subject", nullable = false, length = 255)
    private String createdBySubject;

    @Column(name = "last_updated_by_subject", nullable = false, length = 255)
    private String lastUpdatedBySubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (startedAt == null) {
            startedAt = scheduledStart != null ? scheduledStart : createdAt;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
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

    /**
     * Returns {@code orgId} when populated (canonical write path) else
     * falls back to {@code tenantId} (legacy rows). The V1 trigger keeps
     * {@code orgId == tenantId} when both are set, so the two paths are
     * observably equivalent for canonical rows.
     */
    public UUID getEffectiveOrgId() {
        return orgId != null ? orgId : tenantId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public MeetingStatus getStatus() {
        return status;
    }

    public void setStatus(MeetingStatus status) {
        this.status = status;
    }

    public Instant getScheduledStart() {
        return scheduledStart;
    }

    public void setScheduledStart(Instant scheduledStart) {
        this.scheduledStart = scheduledStart;
    }

    public Instant getScheduledEnd() {
        return scheduledEnd;
    }

    public void setScheduledEnd(Instant scheduledEnd) {
        this.scheduledEnd = scheduledEnd;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public String getOrganizerSubject() {
        return organizerSubject;
    }

    public void setOrganizerSubject(String organizerSubject) {
        this.organizerSubject = organizerSubject;
    }

    public String getCreatedBySubject() {
        return createdBySubject;
    }

    public void setCreatedBySubject(String createdBySubject) {
        this.createdBySubject = createdBySubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getLastUpdatedBySubject() {
        return lastUpdatedBySubject;
    }

    public void setLastUpdatedBySubject(String lastUpdatedBySubject) {
        this.lastUpdatedBySubject = lastUpdatedBySubject;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Meeting that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
