package com.example.endpointadmin.remoteaccess.preflight;

/** Durable Faz 22.6 VIEW_ONLY transaction checkpoint states. */
public enum ViewOnlyCheckpointState {
    DECISION_AUTHORIZED,
    LIVE_REVALIDATED,
    ACTIVATED,
    CONSENT_PENDING,
    EVIDENCE_COLLECTED,
    EVIDENCE_VERIFIED,
    FAILURE_CAPTURED,
    ARTIFACTS_STAGED,
    ARTIFACTS_STAGE_FAILED,
    ROLLBACK_PENDING,
    ROLLED_BACK,
    COMPLETED,
    FAILED_CLEAN;

    public boolean mustBeTerminal() {
        return this == COMPLETED || this == FAILED_CLEAN;
    }
}
