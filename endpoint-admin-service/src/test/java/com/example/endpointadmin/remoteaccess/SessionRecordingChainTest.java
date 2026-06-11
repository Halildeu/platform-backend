package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.SessionRecordingChain.Entry;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 C-1 — {@link SessionRecordingChain} tamper-evident WORM hash-chain. */
class SessionRecordingChainTest {

    private SessionRecordingChain seeded() {
        SessionRecordingChain chain = new SessionRecordingChain();
        chain.append(RecordKind.SESSION_START, "hash-a", 1000L);
        chain.append(RecordKind.OPERATOR_COMMAND, "hash-b", 1001L);
        chain.append(RecordKind.SESSION_END, "hash-c", 1002L);
        return chain;
    }

    @Test
    void appendsLinkAndVerify() {
        SessionRecordingChain chain = seeded();
        assertEquals(3, chain.size());
        assertTrue(chain.verifyIntegrity());
        List<Entry> entries = chain.entries();
        // contiguous seq + genesis anchor + each links to the prior
        assertEquals(0, entries.get(0).seq());
        assertEquals(SessionRecordingChain.GENESIS_HASH, entries.get(0).previousHash());
        assertEquals(entries.get(0).entryHash(), entries.get(1).previousHash());
        assertEquals(entries.get(1).entryHash(), entries.get(2).previousHash());
        assertEquals(entries.get(2).entryHash(), chain.headHash());
    }

    @Test
    void anEmptyChainVerifiesWithTheGenesisHead() {
        SessionRecordingChain chain = new SessionRecordingChain();
        assertTrue(chain.verifyIntegrity());
        assertEquals(SessionRecordingChain.GENESIS_HASH, chain.headHash());
        assertEquals(0, chain.size());
    }

    @Test
    void theHeadAdvancesWithEachAppend() {
        SessionRecordingChain chain = new SessionRecordingChain();
        String h0 = chain.headHash();
        chain.append(RecordKind.SESSION_START, "x", 1L);
        String h1 = chain.headHash();
        chain.append(RecordKind.AGENT_OUTPUT, "y", 2L);
        String h2 = chain.headHash();
        assertNotEquals(h0, h1);
        assertNotEquals(h1, h2);
    }

    @Test
    void anAlteredContentHashIsDetected() {
        List<Entry> entries = new ArrayList<>(seeded().entries());
        Entry e = entries.get(1);
        // same sealed entryHash, but the content was changed after the fact → recompute mismatches
        entries.set(1, new Entry(e.seq(), e.timestampMillis(), e.kind(), "TAMPERED", e.previousHash(), e.entryHash()));
        assertFalse(SessionRecordingChain.verifyChain(entries));
    }

    @Test
    void aReorderedChainIsDetected() {
        List<Entry> entries = new ArrayList<>(seeded().entries());
        Collections.swap(entries, 0, 1); // seq no longer contiguous + prev-link broken
        assertFalse(SessionRecordingChain.verifyChain(entries));
    }

    @Test
    void aRemovedEntryIsDetected() {
        List<Entry> entries = new ArrayList<>(seeded().entries());
        entries.remove(1); // leaves a seq gap + a broken prev-link at the next entry
        assertFalse(SessionRecordingChain.verifyChain(entries));
    }

    @Test
    void anIntactExportedChainVerifiesStandalone() {
        // an auditor (or the C-2 out-of-band sink) can verify a persisted chain independently
        assertTrue(SessionRecordingChain.verifyChain(seeded().entries()));
    }

    @Test
    void appendValidatesItsInputs() {
        SessionRecordingChain chain = new SessionRecordingChain();
        assertThrows(IllegalArgumentException.class, () -> chain.append(null, "h", 1L));
        assertThrows(IllegalArgumentException.class, () -> chain.append(RecordKind.KILL, "  ", 1L));
        assertThrows(IllegalArgumentException.class, () -> chain.append(RecordKind.KILL, "h", -1L));
    }
}
