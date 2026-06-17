package com.example.auditretention.archive;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — raised when verify-before-archive
 * detects a per-tenant hash-chain break or tamper (ADR-0042 D4.4 fail-closed).
 * The archival run aborts: no object is written, the cursor does not advance.
 */
public class ChainBreakException extends RuntimeException {
    public ChainBreakException(String message) {
        super(message);
    }
}
