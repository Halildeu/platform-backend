package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressResponse;
import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 D-7 — {@link DuressResponsePolicy} coercion/duress response: fail-SAFE toward human safety. */
class DuressResponsePolicyTest {

    @Test
    void noSignalProceedsNormally() {
        DuressResponse r = DuressResponsePolicy.responseFor(DuressSignal.NONE);
        assertFalse(r.isDuress());
        assertSame(DuressResponsePolicy.PROCEED, r);
        assertFalse(r.terminateSession());
        assertFalse(r.covertAlert());
        assertEquals(Severity.NONE, r.severity());
    }

    @Test
    void anExplicitDuressSignalMandatesTheFullSilentResponse() {
        for (DuressSignal s : new DuressSignal[]{DuressSignal.DURESS_CODE, DuressSignal.PANIC_SIGNAL}) {
            DuressResponse r = DuressResponsePolicy.responseFor(s);
            assertTrue(r.isDuress(), s.name());
            assertTrue(r.terminateSession() && r.terminateAllOperatorSessions(), s.name());
            assertTrue(r.silentToOperator(), s.name());          // the coercer must see a normal-looking outcome
            assertTrue(r.covertAlert(), s.name());
            assertTrue(r.preserveRecording(), s.name());          // evidence is never dropped
            assertTrue(r.requireIncidentReview(), s.name());
            assertEquals(Severity.CRITICAL, r.severity(), s.name());
        }
    }

    @Test
    void ambiguityFailsSafeTowardAssumingDuress() {
        // a missed real duress is catastrophic (operator harm); a false duress only ends one recoverable session
        assertTrue(DuressResponsePolicy.responseFor(DuressSignal.AMBIGUOUS).isDuress());
    }

    @Test
    void aNullSignalIsTreatedAsDuressNeverAsProceed() {
        DuressResponse r = DuressResponsePolicy.responseFor(null);
        assertTrue(r.isDuress());
        assertSame(DuressResponsePolicy.DURESS, r);
    }

    @Test
    void onlyTheNoneSignalEverProceeds() {
        for (DuressSignal s : DuressSignal.values()) {
            boolean isDuress = DuressResponsePolicy.responseFor(s).isDuress();
            assertEquals(s != DuressSignal.NONE, isDuress, s.name());
        }
    }

    @Test
    void theProceedAndDuressConstantsAreTheExpectedShape() {
        assertFalse(DuressResponsePolicy.PROCEED.isDuress());
        DuressResponse d = DuressResponsePolicy.DURESS;
        assertTrue(d.terminateSession() && d.terminateAllOperatorSessions() && d.silentToOperator()
                && d.covertAlert() && d.preserveRecording() && d.requireIncidentReview());
        assertEquals(Severity.CRITICAL, d.severity());
    }
}
