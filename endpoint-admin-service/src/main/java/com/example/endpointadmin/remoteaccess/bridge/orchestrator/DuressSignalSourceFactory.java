package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faz 22.6 D10 Workstream-0 slice-3 (Codex 019ebe06) — selects the {@link TrustEvidenceAssembler.DuressSignalSource}
 * with a blocking matrix at construction (= bean creation = STARTUP fail-fast):
 * <ul>
 *   <li><b>AMBIGUOUS_UNTIL_WIRED</b> — the fail-closed default: no real duress producer wired ⇒ AMBIGUOUS ⇒ the
 *       broker KILLS. Preserved for every non-pilot configuration.</li>
 *   <li><b>PILOT_RISK_ACCEPTED_DISABLED</b> — the {@link PilotRiskAcceptedDuressSignalSource} (asserts NONE so
 *       the broker does not kill). It DISABLES the human-protection kill, so it is built ONLY with an explicit
 *       owner risk-acceptance flag (else fail-fast) and logs a loud warning; valid only for the narrow
 *       named-roster / attended-only / IT-owned pilot.</li>
 * </ul>
 * The real transport duress source is a future (owner-gated) slice — it is not selectable here yet, so a
 * half-wired "real" source can never be silently built.
 */
public final class DuressSignalSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(DuressSignalSourceFactory.class);

    public enum SourceType { AMBIGUOUS_UNTIL_WIRED, PILOT_RISK_ACCEPTED_DISABLED }

    private DuressSignalSourceFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access duress source config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param type              the duress source (null defaults to the fail-closed AMBIGUOUS_UNTIL_WIRED)
     * @param pilotRiskAccepted explicit owner acceptance that the pilot runs WITHOUT duress detection (required
     *                          for PILOT_RISK_ACCEPTED_DISABLED)
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static TrustEvidenceAssembler.DuressSignalSource create(SourceType type, boolean pilotRiskAccepted) {
        SourceType t = type == null ? SourceType.AMBIGUOUS_UNTIL_WIRED : type; // fail-closed default
        switch (t) {
            case AMBIGUOUS_UNTIL_WIRED -> {
                return TrustEvidenceAssembler.DuressSignalSource.AMBIGUOUS_UNTIL_WIRED;
            }
            case PILOT_RISK_ACCEPTED_DISABLED -> {
                if (!pilotRiskAccepted) {
                    throw reject("duress source PILOT_RISK_ACCEPTED_DISABLED DISABLES the human-protection duress "
                            + "kill and REQUIRES explicit owner risk-acceptance "
                            + "(remote-bridge.duress.pilot-risk-accepted=true) — refusing to silently disable it");
                }
                log.warn("DURESS DETECTION DISABLED (PILOT_RISK_ACCEPTED_DISABLED) — owner-risk-accepted: the "
                        + "broker will NOT kill on duress. Valid ONLY for the narrow named-roster / attended-only "
                        + "/ IT-owned pilot; every other deployment MUST use a real duress source.");
                return new PilotRiskAcceptedDuressSignalSource();
            }
            default -> throw reject("unreachable duress source type " + t);
        }
    }
}
