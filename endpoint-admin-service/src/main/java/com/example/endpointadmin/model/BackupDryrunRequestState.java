package com.example.endpointadmin.model;

/**
 * Faz 22.8A.3b (#648) — backup dry-run issuing request state machine.
 *
 * <pre>
 *   PENDING_APPROVAL → APPROVED   (maker-checker: proposer ≠ approver)
 * </pre>
 */
public enum BackupDryrunRequestState {
    PENDING_APPROVAL,
    APPROVED
}
