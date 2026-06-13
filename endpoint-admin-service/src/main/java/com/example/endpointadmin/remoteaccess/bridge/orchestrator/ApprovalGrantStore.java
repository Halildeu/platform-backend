package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;

import java.util.Set;

/**
 * Faz 22.6 D10 Workstream-0 slice-2 (Codex 019ebe06) — the store that holds the capabilities a dual-control
 * approval granted for a specific session-incarnation. The {@link OwnerTokenGate} reads it; the
 * {@link RemoteSessionApprovalRecorder} writes it (only after the E10 dual-control approval ALLOWED).
 *
 * <p><b>Key = full session-incarnation + tenancy boundary</b> (Codex slice-2): sessionId is client-supplied and
 * NOT a security boundary on its own — {@code operatorTenantId} closes cross-tenant fail-open (#612 lesson) and
 * {@code sessionStartEpochMillis} closes same-id reuse after a session is closed/evicted (a later session that
 * reuses the id cannot read an earlier grant). The subject is the RAW operator subject (matching the read path
 * in {@code TrustEvidenceAssembler}); canonicalization stays inside the E10 gate.
 */
public interface ApprovalGrantStore {

    /** The grant key — a well-formed key requires non-blank sessionId / tenant / subject. */
    record ApprovalGrantKey(String sessionId, String operatorTenantId, String operatorSubject,
                            long sessionStartEpochMillis) {
        public ApprovalGrantKey {
            if (sessionId == null || sessionId.isBlank() || operatorTenantId == null || operatorTenantId.isBlank()
                    || operatorSubject == null || operatorSubject.isBlank()) {
                throw new IllegalArgumentException("ApprovalGrantKey requires non-blank sessionId/tenant/subject");
            }
        }
    }

    /**
     * Record the granted capabilities for {@code key}, valid until {@code expiresAtEpochMillis}. The caller
     * ({@link RemoteSessionApprovalRecorder}) has already validated the capabilities (non-empty, pilot, a subset
     * of the request) and the dual-control approval.
     */
    void record(ApprovalGrantKey key, Set<RemoteSessionCapability> capabilities, long expiresAtEpochMillis);

    /**
     * The capabilities granted for {@code key} — fail-closed: an empty set if there is no grant OR the grant has
     * expired ({@code nowEpochMillis >= expiresAt}).
     */
    Set<RemoteSessionCapability> granted(ApprovalGrantKey key, long nowEpochMillis);
}
