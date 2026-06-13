package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.InMemoryAuthzGrantResolver.Grant;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalGate.Outcome;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the approval-flow orchestrator composes the E10 primitives (canonical
 * identity + authz grants + approver≠requester + fatigue cap) into ONE audited decision. The central security
 * property: the grants are resolved SERVER-SIDE from the {@link AuthzGrantResolver} seam (the {@code decide}
 * signature carries no grant booleans), so a client cannot inject {@code canApprove=true}. The grant lookup is
 * on the RAW principal (decision A) — an alias without a raw grant fails CLOSED, locking that choice.
 */
class RemoteSessionApprovalFlowTest {

    private static final long NOW = 1_000L;
    private static final long WINDOW = 60_000L;
    private static final String RES = "res1";

    // identity: u1 owns alias u1-alias; u2/u3/u5 distinct
    private static final Map<String, String> IDENTITY = Map.of(
            "u1", "u1", "u1-alias", "u1", "u2", "u2", "u3", "u3", "u5", "u5");

    // grants on the RAW principal: u1/u2/u3 may request; u2/u3/u1 + the ALIAS u1-alias may approve (u5 holds none)
    private static final Set<Grant> CAN_REQUEST = Set.of(
            new Grant("u1", RES), new Grant("u2", RES), new Grant("u3", RES));
    private static final Set<Grant> CAN_APPROVE = Set.of(
            new Grant("u2", RES), new Grant("u3", RES), new Grant("u1", RES), new Grant("u1-alias", RES));

    private static AuthzGrantResolver grantResolver() {
        return new InMemoryAuthzGrantResolver(CAN_REQUEST, CAN_APPROVE);
    }

    private RemoteSessionApprovalFlow flow(ApprovalFatigueLimiter fatigue) {
        RemoteSessionApprovalGate gate =
                new RemoteSessionApprovalGate(new InMemoryCanonicalIdentityResolver(IDENTITY), fatigue);
        return new RemoteSessionApprovalFlow(grantResolver(), gate);
    }

    @Test
    void aGrantedDistinctUnfatiguedApprovalIsAllowed() {
        assertEquals(Outcome.ALLOWED, flow(new ApprovalFatigueLimiter(2, WINDOW)).decide("u1", "u2", RES, NOW));
    }

    @Test
    void aRequesterLackingCanRequestIsDeniedMissingGrantAndSpendsNoFatigue() {
        RemoteSessionApprovalFlow flow = flow(new ApprovalFatigueLimiter(1, WINDOW));
        // u5 has a resolvable identity but no can_request grant → MISSING_GRANT (not UNRESOLVED_IDENTITY)
        assertEquals(Outcome.DENIED_MISSING_GRANT, flow.decide("u5", "u2", RES, NOW));
        // u2 spent no fatigue on the denied attempt → a valid approval by u2 still succeeds
        assertEquals(Outcome.ALLOWED, flow.decide("u1", "u2", RES, NOW));
    }

    @Test
    void anApproverLackingCanApproveIsDeniedMissingGrant() {
        assertEquals(Outcome.DENIED_MISSING_GRANT,
                flow(new ApprovalFatigueLimiter(1, WINDOW)).decide("u1", "u5", RES, NOW));
    }

    @Test
    void aThrowingGrantResolverIsFailClosedAsMissingGrantAndSpendsNoFatigue() {
        ApprovalFatigueLimiter fatigue = new ApprovalFatigueLimiter(1, WINDOW);
        AuthzGrantResolver throwing = new AuthzGrantResolver() {
            @Override
            public boolean hasCanRequest(String p, String r) {
                throw new IllegalStateException("authz plane boom");
            }

            @Override
            public boolean hasCanApprove(String p, String r) {
                throw new IllegalStateException("authz plane boom");
            }
        };
        RemoteSessionApprovalGate gate =
                new RemoteSessionApprovalGate(new InMemoryCanonicalIdentityResolver(IDENTITY), fatigue);
        RemoteSessionApprovalFlow throwingFlow = new RemoteSessionApprovalFlow(throwing, gate);
        assertEquals(Outcome.DENIED_MISSING_GRANT, throwingFlow.decide("u1", "u2", RES, NOW));
        // the throwing path spent no fatigue → a real flow sharing the limiter still allows u2 to approve
        assertEquals(Outcome.ALLOWED, flow(fatigue).decide("u1", "u2", RES, NOW));
    }

    @Test
    void aBlankOrNullResourceIsDeniedAndSpendsNoFatigue() {
        RemoteSessionApprovalFlow flow = flow(new ApprovalFatigueLimiter(1, WINDOW));
        // no resource → no grant can apply (short-circuit, defense-in-depth) → MISSING_GRANT, no fatigue spent
        assertEquals(Outcome.DENIED_MISSING_GRANT, flow.decide("u1", "u2", "   ", NOW));
        assertEquals(Outcome.DENIED_MISSING_GRANT, flow.decide("u1", "u2", null, NOW));
        assertEquals(Outcome.ALLOWED, flow.decide("u1", "u2", RES, NOW));
    }

    @Test
    void selfApprovalViaAnAliasIsDeniedSelfApproval() {
        // u1 requests (granted), then "approves" under u1-alias (granted to the alias) which canonicalizes to u1
        assertEquals(Outcome.DENIED_SELF_APPROVAL,
                flow(new ApprovalFatigueLimiter(1, WINDOW)).decide("u1", "u1-alias", RES, NOW));
    }

    @Test
    void anApproverOverTheFatigueCapIsDeniedFatigued() {
        RemoteSessionApprovalFlow flow = flow(new ApprovalFatigueLimiter(1, WINDOW)); // 1 approval / window
        assertEquals(Outcome.ALLOWED, flow.decide("u1", "u2", RES, NOW));
        // u2 has hit the cap → a second approval (different requester) within the window is fatigued
        assertEquals(Outcome.DENIED_FATIGUED, flow.decide("u3", "u2", RES, NOW + 1));
    }

    @Test
    void anAliasRequesterWithoutARawGrantIsDeniedMissingGrant_lockingRawLookup() {
        // u1-alias canonicalizes to u1, but the can_request grant is on the RAW "u1" only — a raw lookup on
        // "u1-alias" misses → MISSING_GRANT. If this ever switched to canonical-keyed grant lookup (decision B),
        // u1-alias would resolve to u1 and FIND the grant → this test catches that regression.
        assertEquals(Outcome.DENIED_MISSING_GRANT,
                flow(new ApprovalFatigueLimiter(1, WINDOW)).decide("u1-alias", "u2", RES, NOW));
    }

    @Test
    void theFlowResolvesGrantsFromTheSeamNotTheCaller() {
        // a resolver that grants NOTHING → every decide is MISSING_GRANT; the signature has no grant boolean, so
        // a caller cannot override the seam's answer (the grant-injection bypass is structurally impossible)
        AuthzGrantResolver noGrants = new InMemoryAuthzGrantResolver(Set.of(), Set.of());
        RemoteSessionApprovalGate gate = new RemoteSessionApprovalGate(
                new InMemoryCanonicalIdentityResolver(IDENTITY), new ApprovalFatigueLimiter(2, WINDOW));
        RemoteSessionApprovalFlow flow = new RemoteSessionApprovalFlow(noGrants, gate);
        assertEquals(Outcome.DENIED_MISSING_GRANT, flow.decide("u1", "u2", RES, NOW));
    }

    @Test
    void nullCollaboratorsAreRejectedAtConstruction() {
        RemoteSessionApprovalGate gate = new RemoteSessionApprovalGate(
                new InMemoryCanonicalIdentityResolver(IDENTITY), new ApprovalFatigueLimiter(1, WINDOW));
        assertThrows(NullPointerException.class, () -> new RemoteSessionApprovalFlow(null, gate));
        assertThrows(NullPointerException.class, () -> new RemoteSessionApprovalFlow(grantResolver(), null));
    }
}
