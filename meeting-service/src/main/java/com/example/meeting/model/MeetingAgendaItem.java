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

/** Ordered, tenant-scoped agenda item belonging to a meeting. */
@Entity
@Table(name = "meeting_agenda_items",
        indexes = {
                @Index(name = "idx_meeting_agenda_items_meeting_order",
                        columnList = "meeting_id,position_index,created_at,id"),
                @Index(name = "idx_meeting_agenda_items_org_id", columnList = "org_id")
        })
public class MeetingAgendaItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "position_index", nullable = false)
    private Integer position;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "detail", length = 4000)
    private String detail;

    @Column(name = "owner_subject", length = 255)
    private String ownerSubject;

    @Column(name = "planned_duration_minutes")
    private Integer plannedDurationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MeetingAgendaItemStatus status = MeetingAgendaItemStatus.PENDING;

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

    public UUID getId() { return id; }
    public UUID getMeetingId() { return meetingId; }
    public void setMeetingId(UUID meetingId) { this.meetingId = meetingId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getEffectiveOrgId() { return orgId != null ? orgId : tenantId; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getOwnerSubject() { return ownerSubject; }
    public void setOwnerSubject(String ownerSubject) { this.ownerSubject = ownerSubject; }
    public Integer getPlannedDurationMinutes() { return plannedDurationMinutes; }
    public void setPlannedDurationMinutes(Integer plannedDurationMinutes) {
        this.plannedDurationMinutes = plannedDurationMinutes;
    }
    public MeetingAgendaItemStatus getStatus() { return status; }
    public void setStatus(MeetingAgendaItemStatus status) { this.status = status; }
    public String getCreatedBySubject() { return createdBySubject; }
    public void setCreatedBySubject(String createdBySubject) { this.createdBySubject = createdBySubject; }
    public String getLastUpdatedBySubject() { return lastUpdatedBySubject; }
    public void setLastUpdatedBySubject(String lastUpdatedBySubject) {
        this.lastUpdatedBySubject = lastUpdatedBySubject;
    }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof MeetingAgendaItem that
                && id != null
                && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
