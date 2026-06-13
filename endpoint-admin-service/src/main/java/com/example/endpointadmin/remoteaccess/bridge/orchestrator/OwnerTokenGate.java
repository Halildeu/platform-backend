package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;

import java.util.Set;

/**
 * Faz 22.6 T-4a-ii slice-3 (Codex 019ebbfa/019ebc7e) — the AUTHORITATIVE source of a session's GRANTED
 * capabilities: an owner-signed pilot token that an authorized owner issued for THIS session/operator/device,
 * naming exactly which pilot capabilities it confers. The granted set is what the
 * {@code RemoteBridgeTrustEvidence} carries into the policy engine.
 *
 * <p><b>Default DENY-ALL ({@link #DENY_ALL}):</b> with no owner-token mechanism wired, an enabled broker
 * grants NOTHING — every operation is denied (no capability granted ⇒ the engine refuses). This is the
 * fail-closed floor: the real owner-signed-token verifier is a later slice (the live pilot, ADR-0034 §13).
 *
 * <p><b>{@code requested ∩ PILOT_ALLOWED} is a PREFILTER, NOT the grant source</b> (Codex): what the operator
 * REQUESTS only narrows; what the OWNER granted is authoritative. {@link #effectiveGrant} intersects the
 * gate's grant with the request and the pilot allowlist, so a token can never confer a non-pilot capability
 * and a request can never widen beyond the owner's grant.
 */
public interface OwnerTokenGate {

    /**
     * The lookup context — the FULL session-incarnation + tenancy boundary, so a grant can never be read by a
     * different tenant or by a later session that reused the same id (Codex slice-2: sessionId is client-supplied
     * and NOT a security boundary on its own; {@code operatorTenantId} + {@code sessionStartEpochMillis} pin it).
     */
    record OwnerGrantContext(String sessionId, String operatorTenantId, String operatorSubject,
                             long sessionStartEpochMillis) {
    }

    /**
     * The capabilities granted for THIS session-incarnation. MUST be total + fail-closed: no grant (or an
     * expired one) ⇒ empty set; never a non-pilot capability. {@code nowEpochMillis} lets a backing store expire
     * stale grants.
     */
    Set<RemoteSessionCapability> grantedCapabilities(OwnerGrantContext context, long nowEpochMillis);

    /** No grant mechanism wired → grant nothing (every operation denied). The fail-closed default. */
    OwnerTokenGate DENY_ALL = (context, nowEpochMillis) -> Set.of();

    /**
     * The effective grant a session may exercise: the owner's grant, intersected with what the operator
     * requested AND the pilot allowlist. Owner-authoritative (a request can't widen it), pilot-bounded (a
     * token can't confer a non-pilot capability).
     */
    static Set<RemoteSessionCapability> effectiveGrant(Set<RemoteSessionCapability> ownerGranted,
                                                       Set<RemoteSessionCapability> requested) {
        if (ownerGranted == null || requested == null) {
            return Set.of();
        }
        return ownerGranted.stream()
                .filter(requested::contains)
                .filter(WireContract::isPilotCapability)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
