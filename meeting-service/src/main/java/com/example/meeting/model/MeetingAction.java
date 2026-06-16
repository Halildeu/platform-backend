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
 * An action item extracted from / recorded for a {@link Meeting} (1:N) —
 * Faz 24. See {@link MeetingSession} for the flat-parent-FK +
 * {@code ON DELETE CASCADE} convention and {@link Meeting} for the
 * org_id compat pattern.
 */
@Entity
@Table(name = "meeting_actions",
        indexes = {
                @Index(name = "idx_meeting_actions_meeting_id", columnList = "meeting_id"),
                @Index(name = "idx_meeting_actions_org_id", columnList = "org_id")
        })
public class MeetingAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Column(name = "assignee_subject", length = 255)
    private String assigneeSubject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MeetingActionStatus status = MeetingActionStatus.OPEN;

    @Column(name = "due_at")
    private Instant dueAt;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(UUID meetingId) {
        this.meetingId = meetingId;
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

    public UUID getEffectiveOrgId() {
        return orgId != null ? orgId : tenantId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAssigneeSubject() {
        return assigneeSubject;
    }

    public void setAssigneeSubject(String assigneeSubject) {
        this.assigneeSubject = assigneeSubject;
    }

    public MeetingActionStatus getStatus() {
        return status;
    }

    public void setStatus(MeetingActionStatus status) {
        this.status = status;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
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
        if (!(o instanceof MeetingAction that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
