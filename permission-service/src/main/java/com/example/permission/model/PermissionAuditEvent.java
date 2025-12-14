package com.example.permission.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "permission_audit_events", indexes = {
        @Index(name = "idx_permission_audit_events_type", columnList = "event_type"),
        @Index(name = "idx_permission_audit_events_user", columnList = "user_email"),
        @Index(name = "idx_permission_audit_events_service", columnList = "service")
})
public class PermissionAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "performed_by")
    private Long performedBy;

    @Column(name = "details", length = 2000)
    private String details;

    @Column(name = "user_email")
    private String userEmail;

    @Column(name = "service")
    private String service;

    @Column(name = "level")
    private String level;

    @Column(name = "action")
    private String action;

    @Column(name = "correlation_id")
    private String correlationId;

    @Lob
    @Column(name = "metadata")
    private String metadata;

    @Lob
    @Column(name = "before_state")
    private String beforeState;

    @Lob
    @Column(name = "after_state")
    private String afterState;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    public PermissionAuditEvent() {
    }

    public PermissionAuditEvent(String eventType, Long performedBy, String details) {
        this.eventType = eventType;
        this.performedBy = performedBy;
        this.details = details;
    }

    public PermissionAuditEvent(String eventType,
                                Long performedBy,
                                String details,
                                String userEmail,
                                String service,
                                String level,
                                String action,
                                String correlationId,
                                String metadata,
                                String beforeState,
                                String afterState) {
        this.eventType = eventType;
        this.performedBy = performedBy;
        this.details = details;
        this.userEmail = userEmail;
        this.service = service;
        this.level = level;
        this.action = action;
        this.correlationId = correlationId;
        this.metadata = metadata;
        this.beforeState = beforeState;
        this.afterState = afterState;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Long getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(Long performedBy) {
        this.performedBy = performedBy;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getBeforeState() {
        return beforeState;
    }

    public void setBeforeState(String beforeState) {
        this.beforeState = beforeState;
    }

    public String getAfterState() {
        return afterState;
    }

    public void setAfterState(String afterState) {
        this.afterState = afterState;
    }
}
