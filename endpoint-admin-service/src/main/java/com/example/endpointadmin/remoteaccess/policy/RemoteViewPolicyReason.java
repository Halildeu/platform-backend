package com.example.endpointadmin.remoteaccess.policy;

/** Stable, non-sensitive fail-closed policy reason codes. */
public enum RemoteViewPolicyReason {
    POLICY_UNAVAILABLE,
    POLICY_INVALID,
    POLICY_STALE,
    POLICY_EXPIRED,
    POLICY_REVOKED,
    POLICY_SESSION_MISMATCH,
    NOTICE_DIGEST_MISMATCH,
    POLICY_AGENT_UNSUPPORTED,
    POLICY_KEY_UNKNOWN,
    POLICY_KEY_REVOKED,
    POLICY_SIGNATURE_FAILED,
    POLICY_AUDIT_FAILED
}
