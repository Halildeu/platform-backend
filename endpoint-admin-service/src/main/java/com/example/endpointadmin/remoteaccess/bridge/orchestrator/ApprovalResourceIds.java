package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

/**
 * Faz 22.6 D10 Workstream-0 slice-2 (Codex 019ebe06) — builds the TENANT-SCOPED authz resource id for a remote
 * session, so the dual-control approval resolves grants against {@code remote_session:<tenant>:<sessionId>} and
 * a bare sessionId is never a global authorization boundary (#612 lesson).
 */
public final class ApprovalResourceIds {

    private ApprovalResourceIds() {
    }

    public static String remoteSession(String operatorTenantId, String sessionId) {
        return "remote_session:" + operatorTenantId + ":" + sessionId;
    }
}
