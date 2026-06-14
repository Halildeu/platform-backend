package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.Size;

/**
 * Faz 22.8A.3b (#648) — approve a backup dry-run request (maker-checker:
 * approver ≠ proposer). Optional approve-time {@code reason} (path-free
 * scanned).
 */
public record AdminBackupDryrunRequestApproval(
        @Size(max = 512) String reason) {
}
