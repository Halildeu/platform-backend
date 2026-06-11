package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.RecordingAnchorVerifier.AnchorVerdict;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 C-3 — {@link SessionRecorder}: ties the chain + durable sink + anchor and exposes the
 * {@code recordingWriterAck} health (fail-closed + latching on a sink outage).
 */
class SessionRecorderTest {

    private static final String ALG = "SHA256withECDSA";
    private final KeyPair keyPair = ecKeyPair();
    private final RecordingAnchorSigner signer = new RecordingAnchorSigner("sess-1", keyPair.getPrivate(), ALG);

    private static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void recordsToChainAndSinkAndIsHealthy() {
        InMemoryRecordingSink sink = new InMemoryRecordingSink();
        SessionRecorder recorder = new SessionRecorder(sink, signer);
        assertTrue(recorder.isHealthy());
        assertTrue(recorder.record(RecordKind.SESSION_START, "h0", 100L));
        assertTrue(recorder.record(RecordKind.OPERATOR_COMMAND, "h1", 200L));
        assertEquals(2, recorder.recordedCount());
        assertEquals(2, sink.written().size());
        assertTrue(recorder.isHealthy());
    }

    @Test
    void aSinkOutageLatchesTheRecorderUnhealthy() {
        InMemoryRecordingSink sink = new InMemoryRecordingSink();
        SessionRecorder recorder = new SessionRecorder(sink, signer);
        assertTrue(recorder.record(RecordKind.SESSION_START, "h0", 100L));
        sink.setWritable(false);
        assertFalse(recorder.record(RecordKind.AGENT_OUTPUT, "h1", 200L)); // write fails → latched
        assertFalse(recorder.isHealthy());
        // even after the sink recovers, the recorder stays unhealthy (the gap already happened)
        sink.setWritable(true);
        assertFalse(recorder.isHealthy());
        assertFalse(recorder.record(RecordKind.SESSION_END, "h2", 300L));
    }

    @Test
    void aSinkThatIsNotWritableIsUnhealthy() {
        InMemoryRecordingSink sink = new InMemoryRecordingSink();
        sink.setWritable(false);
        SessionRecorder recorder = new SessionRecorder(sink, signer);
        assertFalse(recorder.isHealthy());
        assertFalse(recorder.record(RecordKind.SESSION_START, "h0", 100L));
    }

    @Test
    void theAnchorReflectsTheRecordedStateAndVerifiesConsistent() {
        InMemoryRecordingSink sink = new InMemoryRecordingSink();
        SessionRecorder recorder = new SessionRecorder(sink, signer);
        recorder.record(RecordKind.SESSION_START, "h0", 100L);
        recorder.record(RecordKind.AGENT_OUTPUT, "h1", 200L);
        RecordingAnchor anchor = recorder.anchor(500L);
        assertEquals(2, anchor.count());
        assertEquals(recorder.headHash(), anchor.headHash());
        RecordingAnchorVerifier verifier = new RecordingAnchorVerifier(keyPair.getPublic(), ALG);
        assertTrue(verifier.verifySignature(anchor));
        assertEquals(AnchorVerdict.CONSISTENT, verifier.audit(reconstructChainFromSink(sink), anchor));
    }

    /** Rebuild a chain from what the sink durably holds — proves the sink mirrors the recorder's chain. */
    private static SessionRecordingChain reconstructChainFromSink(InMemoryRecordingSink sink) {
        SessionRecordingChain rebuilt = new SessionRecordingChain();
        for (SessionRecordingChain.Entry e : sink.written()) {
            rebuilt.append(e.kind(), e.contentHash(), e.timestampMillis());
        }
        return rebuilt;
    }

    @Test
    void ctorRejectsNulls() {
        assertThrows(IllegalArgumentException.class, () -> new SessionRecorder(null, signer));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionRecorder(new InMemoryRecordingSink(), null));
    }

    @Test
    void aHealthProbeThatThrowsIsTreatedUnhealthy() {
        // Codex 019eb7d6: isHealthy() must not propagate a probe exception (fail-closed instead)
        RecordingSink throwingSink = new RecordingSink() {
            @Override
            public void append(SessionRecordingChain.Entry entry) {
            }

            @Override
            public boolean isWritable() {
                throw new IllegalStateException("probe blew up");
            }
        };
        SessionRecorder recorder = new SessionRecorder(throwingSink, signer);
        assertFalse(recorder.isHealthy());
    }

    @Test
    void anchoringAnUnhealthyRecorderIsRefused() {
        // Codex 019eb7d6: don't anchor a broken recorder (its chain may be ahead of the durable sink)
        InMemoryRecordingSink sink = new InMemoryRecordingSink();
        SessionRecorder recorder = new SessionRecorder(sink, signer);
        recorder.record(RecordKind.SESSION_START, "h0", 100L);
        sink.setWritable(false);
        recorder.record(RecordKind.AGENT_OUTPUT, "h1", 200L); // fails → latched unhealthy
        assertThrows(IllegalStateException.class, () -> recorder.anchor(500L));
    }
}
