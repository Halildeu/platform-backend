package com.example.permission.outbox;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Faz 4-a: Outbox entry for durable OpenFGA tuple sync.
 * Written by RoleChangeEventHandler, polled by OutboxPoller.
 */
@Entity
@Table(name = "tuple_sync_outbox")
public class TupleSyncOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "error_message")
    private String errorMessage;

    protected TupleSyncOutboxEntry() {}

    public TupleSyncOutboxEntry(Long roleId) {
        this.roleId = roleId;
    }

    public Long getId() { return id; }
    public Long getRoleId() { return roleId; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getErrorMessage() { return errorMessage; }

    public void markProcessing() {
        this.status = "PROCESSING";
        this.attempts++;
        this.updatedAt = LocalDateTime.now();
    }

    public void markDone() {
        this.status = "DONE";
        this.updatedAt = LocalDateTime.now();
        this.errorMessage = null;
    }

    public void markFailed(String error) {
        this.status = attempts >= maxAttempts ? "FAILED" : "PENDING";
        this.errorMessage = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        this.updatedAt = LocalDateTime.now();
    }
}
