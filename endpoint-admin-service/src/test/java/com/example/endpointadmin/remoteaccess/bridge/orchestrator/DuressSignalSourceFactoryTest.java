package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.DuressSignalSourceFactory.SourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 D10 slice-3 (Codex 019ebe06) — the duress source factory matrix: AMBIGUOUS_UNTIL_WIRED is the
 * fail-closed default (allowed everywhere, including prod); PILOT_RISK_ACCEPTED_DISABLED — which DISABLES the
 * human-protection kill — builds ONLY outside a prod-like profile AND with an explicit owner risk-acceptance
 * flag, else fail-fast.
 */
class DuressSignalSourceFactoryTest {

    @Test
    void ambiguousUntilWiredIsTheDefaultAndClassifiesAmbiguous() {
        assertSame(TrustEvidenceAssembler.DuressSignalSource.AMBIGUOUS_UNTIL_WIRED,
                DuressSignalSourceFactory.create(SourceType.AMBIGUOUS_UNTIL_WIRED, false, false));
        assertEquals(DuressSignal.AMBIGUOUS,
                DuressSignalSourceFactory.create(SourceType.AMBIGUOUS_UNTIL_WIRED, false, false).classify("s", 1L));
    }

    @Test
    void ambiguousDefaultStillBuildsInAProdLikeProfile() {
        // the fail-closed default is allowed everywhere — only the DISABLED mode is prod-forbidden
        assertSame(TrustEvidenceAssembler.DuressSignalSource.AMBIGUOUS_UNTIL_WIRED,
                DuressSignalSourceFactory.create(SourceType.AMBIGUOUS_UNTIL_WIRED, false, true));
    }

    @Test
    void aNullTypeDefaultsToAmbiguousEvenInProd() {
        assertSame(TrustEvidenceAssembler.DuressSignalSource.AMBIGUOUS_UNTIL_WIRED,
                DuressSignalSourceFactory.create(null, true, true));
    }

    @Test
    void pilotDisabledBuildsInNonProdWithRiskAcceptanceAndAssertsNone() {
        TrustEvidenceAssembler.DuressSignalSource source =
                DuressSignalSourceFactory.create(SourceType.PILOT_RISK_ACCEPTED_DISABLED, true, false);
        assertInstanceOf(PilotRiskAcceptedDuressSignalSource.class, source);
        assertEquals(DuressSignal.NONE, source.classify("s", 1L)); // NONE → broker does not kill
    }

    @Test
    void pilotDisabledWithoutRiskAcceptanceFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> DuressSignalSourceFactory.create(SourceType.PILOT_RISK_ACCEPTED_DISABLED, false, false));
    }

    @Test
    void pilotDisabledIsRejectedInAProductionLikeProfileEvenWithRiskAcceptance() {
        // disabling the human-protection kill is forbidden in prod even with the owner flag
        assertThrows(IllegalStateException.class,
                () -> DuressSignalSourceFactory.create(SourceType.PILOT_RISK_ACCEPTED_DISABLED, true, true));
    }
}
