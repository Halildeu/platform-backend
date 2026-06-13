package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;

import java.util.Objects;
import java.util.Set;

/**
 * Faz 22.6 D10 Workstream-0 slice-2 (Codex 019ebe06) — the real {@link OwnerTokenGate}: the granted capabilities
 * come from a dual-control APPROVAL recorded in the {@link ApprovalGrantStore} (written by the
 * {@link RemoteSessionApprovalRecorder} only after the E10 approval ALLOWED). Replaces the {@code DENY_ALL}
 * floor once activated.
 *
 * <p><b>Fail-closed read:</b> an ill-formed context (null / blank sessionId / tenant / subject) returns an empty
 * grant without building the key (never throws); a missing or expired grant returns empty. The key is the FULL
 * session-incarnation (sessionId + tenant + RAW subject + sessionStart) so a different tenant or a reused
 * sessionId cannot read this grant.
 */
public final class ApprovalBackedOwnerTokenGate implements OwnerTokenGate {

    private final ApprovalGrantStore store;

    public ApprovalBackedOwnerTokenGate(ApprovalGrantStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Set<RemoteSessionCapability> grantedCapabilities(OwnerGrantContext context, long nowEpochMillis) {
        if (context == null || blank(context.sessionId()) || blank(context.operatorTenantId())
                || blank(context.operatorSubject())) {
            return Set.of(); // fail-closed: an ill-formed context confers nothing
        }
        ApprovalGrantStore.ApprovalGrantKey key = new ApprovalGrantStore.ApprovalGrantKey(
                context.sessionId(), context.operatorTenantId(), context.operatorSubject(),
                context.sessionStartEpochMillis());
        return store.granted(key, nowEpochMillis);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
