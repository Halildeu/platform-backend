package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.RecordingAnchorSigner;
import com.example.endpointadmin.remoteaccess.RecordingSink;
import com.example.endpointadmin.remoteaccess.SessionRecorder;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.Entry;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AuditEvent;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-4a-ii slice-3b (Codex 019ebc7e) — the durable-audit adapter is isolated against a fake
 * {@link RecordingSink} + a REAL {@link SessionRecorder}; the DB sink is proven in its own Postgres IT, so the
 * contract under test here is purely: durable-fail → {@link RemoteBridgeAuditSink#record} throws (fail-closed),
 * kind mapping, and session-keyed recorder reuse.
 */
class DurableRemoteBridgeAuditSinkTest {

    private static final String ALG = "SHA256withECDSA";
    private final KeyPair keyPair = ecKeyPair();

    private static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A capturing in-memory sink: every append durably "commits" and is recorded for assertions. */
    private static final class CapturingSink implements RecordingSink {
        final List<Entry> entries = new ArrayList<>();

        @Override
        public void append(Entry entry) {
            entries.add(entry);
        }

        @Override
        public boolean isWritable() {
            return true;
        }
    }

    /** A sink whose durable write never commits (throws) — the recorder must report fail. */
    private static final class ThrowingSink implements RecordingSink {
        @Override
        public void append(Entry entry) throws RecordingSinkException {
            throw new RecordingSinkException("durable store unreachable");
        }

        @Override
        public boolean isWritable() {
            return false;
        }
    }

    private SessionRecorder recorderWith(RecordingSink sink, String sessionId) {
        return new SessionRecorder(sink, new RecordingAnchorSigner(sessionId, keyPair.getPrivate(), ALG));
    }

    @Test
    void aCommittedDecisionIsDurablyRecordedAsPolicyEvent() {
        CapturingSink sink = new CapturingSink();
        DurableRemoteBridgeAuditSink durable =
                new DurableRemoteBridgeAuditSink(id -> recorderWith(sink, id));

        durable.record(new AuditEvent("sess-1", "PERMIT_ISSUED", "h1", 1_000L));

        assertEquals(1, sink.entries.size());
        assertEquals(RecordKind.POLICY_EVENT, sink.entries.get(0).kind());
        assertEquals("h1", sink.entries.get(0).contentHash());
    }

    @Test
    void aKillEventIsRecordedAsKindKill() {
        CapturingSink sink = new CapturingSink();
        DurableRemoteBridgeAuditSink durable =
                new DurableRemoteBridgeAuditSink(id -> recorderWith(sink, id));

        durable.record(new AuditEvent("sess-1", "KILL:DURESS", "h9", 2_000L));

        assertEquals(RecordKind.KILL, sink.entries.get(0).kind());
    }

    @Test
    void agentSessionEndIsDurablyRecordedAsSessionEnd() {
        CapturingSink sink = new CapturingSink();
        DurableRemoteBridgeAuditSink durable =
                new DurableRemoteBridgeAuditSink(id -> recorderWith(sink, id));

        durable.recordSessionEnd("sess-1", "h9", 2_000L);

        assertEquals(1, sink.entries.size());
        assertEquals(RecordKind.SESSION_END, sink.entries.get(0).kind());
        assertEquals("h9", sink.entries.get(0).contentHash());
    }

    @Test
    void anOperationIdThatMerelyContainsKillIsNotMisclassified() {
        // the broker records decisions as "ALLOW_DECISION:<operationId>" — an opId containing "kill" must
        // map to POLICY_EVENT, NOT KILL (prefix-match, not substring — Codex slice-3b note)
        CapturingSink sink = new CapturingSink();
        DurableRemoteBridgeAuditSink durable =
                new DurableRemoteBridgeAuditSink(id -> recorderWith(sink, id));

        durable.record(new AuditEvent("sess-1", "ALLOW_DECISION:kill-pod", "h1", 1_000L));

        assertEquals(RecordKind.POLICY_EVENT, sink.entries.get(0).kind());
    }

    @Test
    void aDurableWriteFailureThrowsSoTheBrokerIssuesNoPermit() {
        DurableRemoteBridgeAuditSink durable =
                new DurableRemoteBridgeAuditSink(id -> recorderWith(new ThrowingSink(), id));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> durable.record(new AuditEvent("sess-1", "PERMIT_ISSUED", "h1", 1_000L)));
        assertTrue(ex.getMessage().contains("did not commit"));
    }

    @Test
    void aBlankOrNullSessionFailsClosed() {
        DurableRemoteBridgeAuditSink durable =
                new DurableRemoteBridgeAuditSink(id -> recorderWith(new CapturingSink(), id));

        assertThrows(IllegalStateException.class,
                () -> durable.record(new AuditEvent("", "PERMIT_ISSUED", "h", 1L)));
        assertThrows(IllegalStateException.class,
                () -> durable.record(new AuditEvent(null, "PERMIT_ISSUED", "h", 1L)));
        assertThrows(IllegalStateException.class,
                () -> durable.record(null));
    }

    @Test
    void aNullRecorderFromTheFactoryFailsClosed() {
        DurableRemoteBridgeAuditSink durable = new DurableRemoteBridgeAuditSink(id -> null);
        assertThrows(IllegalStateException.class,
                () -> durable.record(new AuditEvent("sess-1", "PERMIT_ISSUED", "h", 1L)));
    }

    @Test
    void oneRecorderPerSessionIsCreatedOnceAndReused() {
        AtomicInteger factoryCalls = new AtomicInteger();
        CapturingSink sink = new CapturingSink();
        Function<String, SessionRecorder> factory = id -> {
            factoryCalls.incrementAndGet();
            return recorderWith(sink, id);
        };
        DurableRemoteBridgeAuditSink durable = new DurableRemoteBridgeAuditSink(factory);

        durable.record(new AuditEvent("sess-1", "DECISION", "h1", 1L));
        durable.record(new AuditEvent("sess-1", "DECISION", "h2", 2L)); // same session → reuse
        durable.record(new AuditEvent("sess-2", "DECISION", "h3", 3L)); // new session → new recorder

        assertEquals(2, factoryCalls.get(), "factory called once per distinct session");
    }

    @Test
    void aLatchedRecorderStaysFailClosedOnEverySubsequentEvent() {
        // once the durable write breaks, the recorder latches unhealthy → every later record must keep throwing
        DurableRemoteBridgeAuditSink durable =
                new DurableRemoteBridgeAuditSink(id -> recorderWith(new ThrowingSink(), id));

        assertThrows(IllegalStateException.class,
                () -> durable.record(new AuditEvent("sess-1", "DECISION", "h1", 1L)));
        assertThrows(IllegalStateException.class,
                () -> durable.record(new AuditEvent("sess-1", "DECISION", "h2", 2L)));
    }

    @Test
    void chainIntegrityHoldsAcrossMultipleCommittedEvents() {
        CapturingSink sink = new CapturingSink();
        DurableRemoteBridgeAuditSink durable =
                new DurableRemoteBridgeAuditSink(id -> recorderWith(sink, id));

        durable.record(new AuditEvent("sess-1", "ALLOW_DECISION:op-1", "h1", 1L));
        durable.record(new AuditEvent("sess-1", "KILL:DURESS", "h2", 2L));

        SessionRecordingChain rebuilt = new SessionRecordingChain();
        for (Entry e : sink.entries) {
            rebuilt.append(e.kind(), e.contentHash(), e.timestampMillis());
        }
        assertTrue(rebuilt.verifyIntegrity());
        assertEquals(2, sink.entries.size());
    }
}
