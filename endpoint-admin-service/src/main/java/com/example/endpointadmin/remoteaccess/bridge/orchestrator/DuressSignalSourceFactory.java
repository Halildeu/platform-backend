package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faz 22.6 D10 Workstream-0 slice-3 (Codex 019ebe06) — selects the {@link TrustEvidenceAssembler.DuressSignalSource}
 * with a blocking matrix at construction (= bean creation = STARTUP fail-fast):
 * <ul>
 *   <li><b>AMBIGUOUS_UNTIL_WIRED</b> — the fail-closed default: no real duress producer wired ⇒ AMBIGUOUS ⇒ the
 *       broker KILLS. Preserved for every non-pilot configuration.</li>
 *   <li><b>SESSION_SIGNAL</b> — reads a fresh, session-scoped signal recorded through the authenticated operator
 *       channel. Missing or stale records classify as AMBIGUOUS, so the broker still kills until the real source
 *       has explicitly produced NONE or a duress signal for that session.</li>
 *   <li><b>PILOT_RISK_ACCEPTED_DISABLED</b> — the {@link PilotRiskAcceptedDuressSignalSource} (asserts NONE so
 *       the broker does not kill). It DISABLES the human-protection kill, so it is built ONLY with an explicit
 *       owner risk-acceptance flag (else fail-fast) and logs a loud warning; valid only for the narrow
 *       named-roster / attended-only / IT-owned pilot.</li>
 * </ul>
 * A half-wired real source can never be silently built: SESSION_SIGNAL requires a live store, and its missing
 * record semantics are AMBIGUOUS rather than "clean".
 */
public final class DuressSignalSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(DuressSignalSourceFactory.class);

    public enum SourceType { AMBIGUOUS_UNTIL_WIRED, SESSION_SIGNAL, PILOT_RISK_ACCEPTED_DISABLED }

    private DuressSignalSourceFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access duress source config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param type                  the duress source (null defaults to the fail-closed AMBIGUOUS_UNTIL_WIRED)
     * @param pilotRiskAccepted     explicit owner acceptance that the pilot runs WITHOUT duress detection
     *                              (required for PILOT_RISK_ACCEPTED_DISABLED)
     * @param productionLikeProfile when true, PILOT_RISK_ACCEPTED_DISABLED is REFUSED — disabling the
     *                              human-protection kill is forbidden in production even with risk-acceptance;
     *                              a prod deployment MUST use a real duress source (Codex REVISE)
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static TrustEvidenceAssembler.DuressSignalSource create(SourceType type, boolean pilotRiskAccepted,
                                                                   boolean productionLikeProfile) {
        return create(type, pilotRiskAccepted, productionLikeProfile, null);
    }

    public static TrustEvidenceAssembler.DuressSignalSource create(SourceType type, boolean pilotRiskAccepted,
                                                                   boolean productionLikeProfile,
                                                                   SessionDuressSignalStore sessionSignalStore) {
        SourceType t = type == null ? SourceType.AMBIGUOUS_UNTIL_WIRED : type; // fail-closed default
        switch (t) {
            case AMBIGUOUS_UNTIL_WIRED -> {
                return TrustEvidenceAssembler.DuressSignalSource.AMBIGUOUS_UNTIL_WIRED;
            }
            case SESSION_SIGNAL -> {
                if (sessionSignalStore == null) {
                    throw reject("duress source SESSION_SIGNAL requires the session duress signal store — refusing "
                            + "to silently fall back to clean/no-duress");
                }
                log.info("DURESS DETECTION WIRED (SESSION_SIGNAL) — missing or stale session signals classify as "
                        + "AMBIGUOUS and still kill; only a fresh authenticated session signal can return NONE.");
                return sessionSignalStore;
            }
            case PILOT_RISK_ACCEPTED_DISABLED -> {
                if (productionLikeProfile) {
                    throw reject("duress source PILOT_RISK_ACCEPTED_DISABLED DISABLES the human-protection duress "
                            + "kill and is forbidden in a production-like profile — a prod deployment MUST use a "
                            + "real duress source (the disabled mode is for the narrow non-prod pilot only)");
                }
                if (!pilotRiskAccepted) {
                    throw reject("duress source PILOT_RISK_ACCEPTED_DISABLED DISABLES the human-protection duress "
                            + "kill and REQUIRES explicit owner risk-acceptance "
                            + "(remote-bridge.duress.pilot-risk-accepted=true) — refusing to silently disable it");
                }
                log.warn("DURESS DETECTION DISABLED (PILOT_RISK_ACCEPTED_DISABLED) — owner-risk-accepted: the "
                        + "broker will NOT kill on duress. Valid ONLY for the narrow non-prod named-roster / "
                        + "attended-only / IT-owned pilot; every other deployment MUST use a real duress source.");
                return new PilotRiskAcceptedDuressSignalSource();
            }
            default -> throw reject("unreachable duress source type " + t);
        }
    }
}
