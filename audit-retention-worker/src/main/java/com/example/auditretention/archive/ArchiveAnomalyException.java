package com.example.auditretention.archive;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — raised on an S3 object/version
 * anomaly (ADR-0042 D4.7 fail-closed): an unexpected latest version, a
 * ledger-absent object already present at a key (crash-mid-write or external
 * interference), a post-put checksum/retention mismatch, or a recorded version
 * that has gone missing. The run aborts; the cursor does not advance.
 */
public class ArchiveAnomalyException extends RuntimeException {
    public ArchiveAnomalyException(String message) {
        super(message);
    }
}
