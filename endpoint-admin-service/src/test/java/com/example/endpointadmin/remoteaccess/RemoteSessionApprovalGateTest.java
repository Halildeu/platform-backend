package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalGate.Outcome;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the dual-control approval gate composes identity-canonicalization,
 * authz grants, approver≠requester, and the fatigue cap into ONE fail-closed decision; a denial on identity /
 * grant / self-approval never spends the approver's fatigue budget (fatigue is checked + recorded last).
 */
class RemoteSessionApprovalGateTest {

    private static final long NOW = 1_000L;
    private static final long WINDOW = 60_000L;
    // u1 owns alias u1-alias; u2 is distinct
    private static final Map<String, String> MAPPING = Map.of(
            "u1", "u1", "u1-alias", "u1", "u2", "u2", "u3", "u3");

    private static CanonicalIdentityResolver resolver() {
        return new InMemoryCanonicalIdentityResolver(MAPPING);
    }

    private RemoteSessionApprovalGate gate(ApprovalFatigueLimiter fatigue) {
        return new RemoteSessionApprovalGate(resolver(), fatigue);
    }

    @Test
    void aDistinctGrantedUnFatiguedApprovalIsAllowed() {
        Outcome o = gate(new ApprovalFatigueLimiter(2, WINDOW)).decide("u1", "u2", true, true, NOW);
        assertEquals(Outcome.ALLOWED, o);
    }

    @Test
    void anUnresolvableIdentityIsDeniedAndSpendsNoFatigue() {
        ApprovalFatigueLimiter fatigue = new ApprovalFatigueLimiter(1, WINDOW);
        RemoteSessionApprovalGate gate = gate(fatigue);
        assertEquals(Outcome.DENIED_UNRESOLVED_IDENTITY, gate.decide("u1", "unmapped", true, true, NOW));
        assertEquals(Outcome.DENIED_UNRESOLVED_IDENTITY, gate.decide("unmapped", "u2", true, true, NOW));
        // u2's fatigue budget is untouched — a later valid approval by u2 still succeeds
        assertEquals(Outcome.ALLOWED, gate.decide("u1", "u2", true, true, NOW));
    }

    @Test
    void aMissingGrantIsDeniedAndSpendsNoFatigue() {
        ApprovalFatigueLimiter fatigue = new ApprovalFatigueLimiter(1, WINDOW);
        RemoteSessionApprovalGate gate = gate(fatigue);
        assertEquals(Outcome.DENIED_MISSING_GRANT, gate.decide("u1", "u2", false, true, NOW)); // no can_request
        assertEquals(Outcome.DENIED_MISSING_GRANT, gate.decide("u1", "u2", true, false, NOW)); // no can_approve
        // u2 never spent fatigue on the denied attempts → a valid approval still succeeds
        assertEquals(Outcome.ALLOWED, gate.decide("u1", "u2", true, true, NOW));
    }

    @Test
    void selfApprovalViaAnAliasIsDeniedAndSpendsNoFatigue() {
        ApprovalFatigueLimiter fatigue = new ApprovalFatigueLimiter(1, WINDOW);
        RemoteSessionApprovalGate gate = gate(fatigue);
        // u1 requests, then "approves" under u1-alias which canonicalizes to u1 → self-approval
        assertEquals(Outcome.DENIED_SELF_APPROVAL, gate.decide("u1", "u1-alias", true, true, NOW));
        // the alias's canonical (u1) spent no fatigue — u1 can still legitimately approve someone else's request
        assertEquals(Outcome.ALLOWED, gate.decide("u2", "u1", true, true, NOW));
    }

    @Test
    void anApproverOverTheFatigueCapIsDenied() {
        ApprovalFatigueLimiter fatigue = new ApprovalFatigueLimiter(1, WINDOW); // 1 approval / window
        RemoteSessionApprovalGate gate = gate(fatigue);
        assertEquals(Outcome.ALLOWED, gate.decide("u1", "u2", true, true, NOW));
        // u2 has now hit the cap → a second approval (of a different requester) within the window is fatigued
        assertEquals(Outcome.DENIED_FATIGUED, gate.decide("u3", "u2", true, true, NOW + 1));
    }

    @Test
    void theFatigueCapIsKeyedOnTheCanonicalApproverNotTheAlias() {
        ApprovalFatigueLimiter fatigue = new ApprovalFatigueLimiter(1, WINDOW);
        RemoteSessionApprovalGate gate = gate(fatigue);
        assertEquals(Outcome.ALLOWED, gate.decide("u2", "u1", true, true, NOW));
        // approving again under the alias u1-alias (canonical u1) must NOT get a fresh budget
        assertEquals(Outcome.DENIED_FATIGUED, gate.decide("u3", "u1-alias", true, true, NOW + 1));
    }

    @Test
    void aThrowingResolverIsDeniedAsUnresolvedAndSpendsNoFatigue() {
        // this gate is THE dual-control chokepoint → a contract-violating (throwing) resolver is fail-closed
        // here too (Codex REVISE), returning an Outcome rather than propagating a 500/throw
        ApprovalFatigueLimiter fatigue = new ApprovalFatigueLimiter(1, WINDOW);
        CanonicalIdentityResolver throwing = principalId -> {
            throw new IllegalStateException("resolver boom");
        };
        RemoteSessionApprovalGate throwingGate = new RemoteSessionApprovalGate(throwing, fatigue);
        assertEquals(Outcome.DENIED_UNRESOLVED_IDENTITY, throwingGate.decide("u1", "u2", true, true, NOW));
        // the throwing path spent no fatigue for u2 → a real gate sharing the limiter still allows u2 to approve
        RemoteSessionApprovalGate realGate = new RemoteSessionApprovalGate(resolver(), fatigue);
        assertEquals(Outcome.ALLOWED, realGate.decide("u1", "u2", true, true, NOW));
    }

    @Test
    void nullCollaboratorsAreRejectedAtConstruction() {
        assertThrows(NullPointerException.class,
                () -> new RemoteSessionApprovalGate(null, new ApprovalFatigueLimiter(1, WINDOW)));
        assertThrows(NullPointerException.class, () -> new RemoteSessionApprovalGate(resolver(), null));
    }
}
