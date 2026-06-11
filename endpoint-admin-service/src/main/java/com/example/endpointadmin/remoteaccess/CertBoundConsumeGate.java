package com.example.endpointadmin.remoteaccess;

import java.time.Instant;
import java.util.Objects;

/**
 * Faz 22.6 B1.1c — the connect-time enforcement in front of {@link TokenLifecycleStore#consume}: every
 * runtime consume goes through this gate so the cert-binding policy is applied BEFORE the single-use
 * token is burned, and every legacy-unbound issuance is metered
 * ({@link RemoteAccessMetrics#LEGACY_UNBOUND_ISSUANCE} — Codex check-list #2; the store stays pure).
 *
 * <ul>
 *   <li><b>Presented cert</b> → the thumbprint is pinned atomically with the single-use consume
 *       (B1.1a 4-arg overload). A replay under a different cert is already denied by single-use
 *       ({@code ALREADY_USED}).</li>
 *   <li><b>No presented cert + {@link CertBindingGuard.Policy#REQUIRE_BOUND}</b> → fail-closed deny
 *       WITHOUT consulting the store ({@code storeOutcome=null}): a certless connect attempt can neither
 *       burn a victim's single-use token (replay-DoS) nor create a legacy-unbound row.</li>
 *   <li><b>No presented cert + {@link CertBindingGuard.Policy#ALLOW_LEGACY_UNBOUND}</b> → legacy consume
 *       (null thumbprint recorded); an ACCEPTED one increments the migration meter. Whether such a
 *       session may ever go ACTIVE is then the state machine's {@code certBound} precondition — same
 *       policy, same {@link CertBindingGuard} truth table.</li>
 * </ul>
 */
public final class CertBoundConsumeGate {

    private final TokenLifecycleStore store;
    private final CertBindingGuard.Policy policy;
    private final Runnable legacyUnboundMeter;

    /**
     * @param legacyUnboundMeter invoked once per ACCEPTED legacy-unbound consume — the runtime wires
     *                           {@code meters.counter(LEGACY_UNBOUND_ISSUANCE)::increment} here
     */
    public CertBoundConsumeGate(TokenLifecycleStore store, CertBindingGuard.Policy policy,
                                Runnable legacyUnboundMeter) {
        this.store = Objects.requireNonNull(store, "store");
        this.policy = policy == null ? CertBindingGuard.Policy.REQUIRE_BOUND : policy; // fail-closed default
        this.legacyUnboundMeter = Objects.requireNonNull(legacyUnboundMeter, "legacyUnboundMeter");
    }

    /**
     * @param accepted     whether the connect may proceed (the ONLY green signal)
     * @param certDecision the binding decision that was applied
     * @param storeOutcome the store's consume outcome, or {@code null} iff the store was never consulted
     *                     (denied pre-consume — the token was NOT burned)
     */
    public record GateResult(
            boolean accepted,
            CertBindingGuard.Decision certDecision,
            TokenLifecycleStore.ConsumeOutcome storeOutcome) {
    }

    /**
     * Connect-time consume under the cert-binding policy. Fail-closed: a {@code null}/blank presented
     * thumbprint is only ever consumable under the explicit legacy-allow flag.
     */
    public GateResult consume(String jti, Instant expiresAt, Instant now, String presentedThumbprint) {
        if (CertThumbprint.isPresent(presentedThumbprint)) {
            // Pin the presented thumbprint atomically with the single-use claim (B1.1a).
            TokenLifecycleStore.ConsumeOutcome outcome = store.consume(jti, expiresAt, now, presentedThumbprint);
            return new GateResult(outcome.isAccepted(), CertBindingGuard.Decision.BOUND_MATCH, outcome);
        }
        if (policy != CertBindingGuard.Policy.ALLOW_LEGACY_UNBOUND) {
            // Deny BEFORE the store: no burn, no legacy row — the legitimate holder can still connect.
            return new GateResult(false, CertBindingGuard.Decision.UNBOUND_REJECTED, null);
        }
        TokenLifecycleStore.ConsumeOutcome outcome = store.consume(jti, expiresAt, now, null);
        if (outcome.isAccepted()) {
            legacyUnboundMeter.run(); // check-list #2: visibility for every legacy-unbound issuance
        }
        return new GateResult(outcome.isAccepted(), CertBindingGuard.Decision.UNBOUND_ALLOWED, outcome);
    }
}
