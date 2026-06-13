package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;

/**
 * Faz 22.6 D10 Workstream-0 slice-3 (Codex 019ebe06; owner decision 2026-06-13) — a duress source for the
 * narrow attended pilot that asserts {@link DuressSignal#NONE} ("clean"), so the broker does NOT kill for lack
 * of a real duress signal.
 *
 * <p><b>This is a DELIBERATE security reduction, owner-risk-accepted, pilot-ONLY.</b> There is no real
 * duress-signal producer wired yet (the transport duress-classification path is the owner-gated live slice), so
 * the fail-closed default {@code AMBIGUOUS_UNTIL_WIRED} maps the absence of a signal to AMBIGUOUS → KILL — which
 * would block every operation in the pilot. The owner accepted (named-roster + attended-only + IT-owned +
 * no-file-transfer) that the first live pilot runs WITHOUT duress detection; this source encodes exactly that.
 * The {@link DuressSignalSourceFactory} only builds it with an explicit risk-acceptance flag, and the
 * {@code AMBIGUOUS_UNTIL_WIRED} default stays for every non-pilot configuration. The real transport duress
 * source replaces this in the live slice.
 */
public final class PilotRiskAcceptedDuressSignalSource implements TrustEvidenceAssembler.DuressSignalSource {

    @Override
    public DuressSignal classify(String sessionId, long nowEpochMillis) {
        return DuressSignal.NONE; // owner-risk-accepted pilot: assert "clean" (no real producer; pilot-only)
    }
}
