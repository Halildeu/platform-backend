package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionDuressSignalStoreTest {

    @Test
    void missingAndStaleSignalsClassifyAmbiguous() {
        SessionDuressSignalStore store = new SessionDuressSignalStore(100L);

        assertEquals(DuressSignal.AMBIGUOUS, store.classify("s1", 1L));

        assertTrue(store.record("s1", "operator@x", DuressSignal.NONE, 10L).isPresent());
        assertEquals(DuressSignal.NONE, store.classify("s1", 50L));
        assertEquals(DuressSignal.AMBIGUOUS, store.classify("s1", 110L));
    }

    @Test
    void explicitDuressSignalsAreReturnedWhileFresh() {
        SessionDuressSignalStore store = new SessionDuressSignalStore(1_000L);

        assertTrue(store.record("s1", "operator@x", DuressSignal.DURESS_CODE, 10L).isPresent());

        assertEquals(DuressSignal.DURESS_CODE, store.classify("s1", 11L));
    }

    @Test
    void nullAmbiguousOrMalformedWritesAreRejected() {
        SessionDuressSignalStore store = new SessionDuressSignalStore(1_000L);

        assertTrue(store.record("s1", "operator@x", null, 10L).isEmpty());
        assertTrue(store.record("s1", "operator@x", DuressSignal.AMBIGUOUS, 10L).isEmpty());
        assertTrue(store.record(" ", "operator@x", DuressSignal.NONE, 10L).isEmpty());
        assertTrue(store.record("s1", " ", DuressSignal.NONE, 10L).isEmpty());
        assertTrue(store.record("s1", "operator@x", DuressSignal.NONE, -1L).isEmpty());
    }
}
