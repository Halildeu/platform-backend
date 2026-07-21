package com.example.audiogateway.service;

import com.example.audiogateway.dto.TranscriptResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator {@link DirectSttTranscriptResultSink} that folds consecutive committed
 * chunks into readable, sentence-level lines (Faz 24 — transcript readability).
 *
 * <p><b>Why the gateway owns this.</b> Both clients — the recording desktop and the web
 * viewer — and both transports — the live WebSocket path and the REST fallback — pass
 * through this sink. Assembling here gives every surface the same line, and keeps the
 * sentence rules from drifting apart in two separate client repositories.
 *
 * <h2>Order is reconciled before assembly</h2>
 * The forward to live-stt completes out of order, so each session runs a
 * {@link ChunkReorderBuffer} keyed by {@code windowSeq} in front of its
 * {@link SentenceAssembler}. The assembler only ever sees fragments in source order, and
 * each one still carries the context and result it arrived with — a reordered fragment
 * must not be stamped with a later fragment's metadata.
 *
 * <h2>Transport switch is an epoch boundary</h2>
 * The two legs number their windows independently, each from 0. When a session falls back
 * from the live socket to REST, continuing to reorder against the old sequence space
 * would reject fresh speech as a duplicate. A transport change therefore flushes and
 * resets the session's reorder buffer, and closes the open line, before the new leg's
 * first fragment is folded.
 *
 * <h2>Additive, never destructive</h2>
 * The raw chunk is forwarded to the delegate first and unchanged. The assembled line is
 * emitted <i>in addition</i>, carrying {@link DirectSttTranscriptResultContext.Assembly}
 * so a consumer can tell the two apart and trace a line back to its fragments. Nothing is
 * replaced or dropped, so a client that has not been taught about the new status keeps
 * working — which is why the feature is also default-off until both clients render it.
 *
 * <h2>One clock per question</h2>
 * A pause by the speaker is a source-audio gap; how long a trailing line has waited is a
 * gateway receipt-clock question. The two are never compared against each other, because
 * the contract allows source window timestamps to be zero or arbitrary.
 *
 * <h2>Per-session serialisation</h2>
 * Every read and mutation of a session's state — including creation, eviction and close —
 * happens inside {@link ConcurrentHashMap#compute} on that session's key, so a concurrent
 * emit, sweep and close cannot interleave, resurrect a removed buffer, or emit a line
 * stamped with another fragment's metadata.
 *
 * <h2>Emission failures are contained</h2>
 * An assembled emission that throws is logged, metered, and swallowed: the raw chunk has
 * already been durably committed, and a readability enhancement must never turn a
 * delivered transcript into a failed one.
 */
public final class SentenceAssemblingSink implements DirectSttTranscriptResultSink {

    private static final Logger log = LoggerFactory.getLogger(SentenceAssemblingSink.class);

    private static final String METRIC_PREFIX = "audio_gateway_direct_stt_";

    /**
     * How long an idle session's state is kept before it is dropped. Generous relative
     * to the idle flush so a speaker who pauses to think does not lose their buffer,
     * bounded so the map cannot grow across long uptimes.
     */
    static final long SESSION_EVICT_AFTER_MS = 300_000L;

    /** How many out-of-order fragments a session holds behind a gap before abandoning it. */
    static final int REORDER_CAPACITY = 64;

    /** How long a reorder gap may stall before it is released on the clock. */
    static final long REORDER_WINDOW_MS = 3_000L;

    private final DirectSttTranscriptResultSink delegate;
    private final SentenceAssemblyPolicy policy;
    private final MeterRegistry meters;
    private final LongSupplier clock;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SentenceAssemblingSink(
            final DirectSttTranscriptResultSink delegate,
            final SentenceAssemblyPolicy policy,
            final MeterRegistry meters) {
        this(delegate, policy, meters, System::currentTimeMillis);
    }

    SentenceAssemblingSink(
            final DirectSttTranscriptResultSink delegate,
            final SentenceAssemblyPolicy policy,
            final MeterRegistry meters,
            final LongSupplier clock) {
        this.delegate = delegate;
        this.policy = policy == null ? SentenceAssemblyPolicy.defaults() : policy;
        this.meters = meters;
        this.clock = clock;
    }

    /** A fragment together with the exact context and result it arrived with. */
    private record Envelope(
            SentenceAssembler.Fragment fragment,
            DirectSttTranscriptResultContext context,
            TranscriptResult result) {}

    /** An assembled line paired with the envelope that closed it. */
    private record Pending(AssembledUtterance utterance, Envelope source) {}

    private final class SessionState {
        private final SentenceAssembler assembler = new SentenceAssembler(policy);
        private ChunkReorderBuffer<Envelope> reorder =
                new ChunkReorderBuffer<>(REORDER_CAPACITY, REORDER_WINDOW_MS);
        private DirectSttTranscriptResultContext.Transport transport;
        /**
         * The most recent envelope actually folded IN SOURCE ORDER. A close that is not
         * triggered by a specific fragment (idle, session end, transport switch) is
         * carried by this one — using the last ARRIVAL instead would stamp the line with
         * an out-of-order fragment's metadata whenever a reordered batch was released.
         */
        private Envelope lastFoldedEnvelope;
        private long touchedAtMs;

        private SessionState(final long nowMs) {
            this.touchedAtMs = nowMs;
        }
    }

    @Override
    public void emit(
            final TranscriptResult result, final DirectSttTranscriptResultContext context) {
        // Durable first, unchanged. Everything below is an addition on top of a result
        // that has already been delivered.
        delegate.emit(result, context);

        if (context == null || context.sessionId() == null || context.sessionId().isBlank()) {
            return;
        }
        // Never re-assemble something this sink itself produced.
        if (context.assembly() != null) {
            return;
        }

        final List<Pending> toEmit = new ArrayList<>();
        try {
            sessions.compute(
                    context.sessionId(),
                    (id, existing) -> {
                        final long receiptMs = clock.getAsLong();
                        final SessionState state =
                                existing == null ? new SessionState(receiptMs) : existing;
                        toEmit.addAll(accept(state, result, context, receiptMs));
                        return state;
                    });
        } catch (final RuntimeException ex) {
            log.warn(
                    "sentence assembly skipped sessionId={} reason={}",
                    context.sessionId(),
                    ex.getClass().getSimpleName());
            return;
        }
        toEmit.forEach(this::emitAssembled);
    }

    /** Fold one arrival into the session. Runs under the map's per-key lock. */
    private List<Pending> accept(
            final SessionState state,
            final TranscriptResult result,
            final DirectSttTranscriptResultContext context,
            final long receiptMs) {
        final List<Pending> pending = new ArrayList<>();
        state.touchedAtMs = receiptMs;

        // A transport switch restarts the window numbering, so the old sequence space
        // must be closed out before the new leg's first fragment is folded.
        if (state.transport != null && state.transport != context.transport()) {
            pending.addAll(foldOrdered(state, state.reorder.flushAll()));
            state.assembler.closeSession().ifPresent(u -> pending.add(pendingOf(state, u)));
            state.reorder = new ChunkReorderBuffer<>(REORDER_CAPACITY, REORDER_WINDOW_MS);
            count("assembly_transport_switch_total", context.transport().name());
        }
        state.transport = context.transport();

        final Envelope envelope =
                new Envelope(
                        new SentenceAssembler.Fragment(
                                eventIdOf(context),
                                result == null ? null : result.text(),
                                context.windowStartedAtMs(),
                                context.windowEndedAtMs(),
                                context.audioDurationMs(),
                                receiptMs),
                        context,
                        result);
        pending.addAll(
                foldOrdered(state, state.reorder.offer(context.windowSeq(), receiptMs, envelope)));
        return pending;
    }

    /**
     * Close a session's buffers and emit whatever is left — the recording ended.
     *
     * <p>Safe to call for an unknown session and safe to call twice; the trailing line is
     * emitted at most once. Callers should invoke this only after the last outstanding STT
     * result for the session has reached the sink (WS {@code drained} / REST terminal
     * completion), so no fragment is stranded in the reorder buffer.
     */
    public void closeSession(final String sessionId) {
        if (sessionId == null) {
            return;
        }
        final List<Pending> toEmit = new ArrayList<>();
        // Remove and drain atomically: a concurrent emit either runs before this and is
        // included, or after it and rebuilds a fresh state — never appends to a buffer
        // that has already been dropped from the map.
        sessions.computeIfPresent(
                sessionId,
                (id, state) -> {
                    toEmit.addAll(foldOrdered(state, state.reorder.flushAll()));
                    state.assembler
                            .closeSession()
                            .ifPresent(u -> toEmit.add(pendingOf(state, u)));
                    return null;
                });
        toEmit.forEach(this::emitAssembled);
    }

    /**
     * Release stalled reorder gaps, close lines whose speaker has stopped, and drop
     * long-idle session state.
     *
     * <p>Without this a trailing line would wait for the next fragment — which never comes
     * once the speaker stops talking.
     */
    public void sweep() {
        final long now = clock.getAsLong();
        final List<Pending> toEmit = new ArrayList<>();
        for (final String sessionId : List.copyOf(sessions.keySet())) {
            sessions.computeIfPresent(
                    sessionId,
                    (id, state) -> {
                        toEmit.addAll(foldOrdered(state, state.reorder.sweep(now)));
                        state.assembler
                                .flushIfIdle(now)
                                .ifPresent(u -> toEmit.add(pendingOf(state, u)));
                        final boolean evict =
                                !state.assembler.hasBufferedText()
                                        && !state.reorder.hasPending()
                                        && now - state.touchedAtMs >= SESSION_EVICT_AFTER_MS;
                        return evict ? null : state;
                    });
        }
        toEmit.forEach(this::emitAssembled);
    }

    /** Test/observability helper — sessions currently holding assembler state. */
    int trackedSessions() {
        return sessions.size();
    }

    /**
     * Fold released envelopes through the assembler, in source order.
     *
     * <p>A payload flagged {@link ChunkReorderBuffer.Release#lateAfterGap()} arrived after
     * its hole was abandoned. Appending it to whatever line is open now would silently
     * produce "2 3 1", so it is emitted as its own line instead — the text survives and
     * the misordering is visible in the provenance rather than hidden inside a sentence.
     *
     * <p>Must hold the session's per-key lock.
     */
    private List<Pending> foldOrdered(
            final SessionState state, final List<ChunkReorderBuffer.Release<Envelope>> released) {
        if (released.isEmpty()) {
            return List.of();
        }
        final List<Pending> pending = new ArrayList<>();
        for (final ChunkReorderBuffer.Release<Envelope> release : released) {
            final Envelope envelope = release.payload();
            if (release.lateAfterGap()) {
                standaloneLateLine(envelope).ifPresent(pending::add);
                continue;
            }
            state.lastFoldedEnvelope = envelope;
            for (final AssembledUtterance utterance : state.assembler.offer(envelope.fragment())) {
                pending.add(new Pending(utterance, envelope));
            }
        }
        return pending;
    }

    /** The out-of-order arrival as a line of its own, marked as such. */
    private java.util.Optional<Pending> standaloneLateLine(final Envelope envelope) {
        final String text =
                envelope.fragment().text() == null ? "" : envelope.fragment().text().strip();
        if (text.isEmpty()) {
            return java.util.Optional.empty();
        }
        count("assembled_lines_total", SentenceAssembler.REASON_LATE_AFTER_GAP);
        return java.util.Optional.of(
                new Pending(
                        new AssembledUtterance(
                                text,
                                List.of(envelope.fragment().eventId()),
                                envelope.fragment().startedAtMs(),
                                envelope.fragment().endedAtMs(),
                                envelope.fragment().speechDurationMs(),
                                SentenceAssembler.REASON_LATE_AFTER_GAP),
                        envelope));
    }

    /**
     * Carrier for a close that no single fragment triggered (idle, session end, transport
     * switch): the last envelope folded in SOURCE order. Snapshotted under the lock.
     */
    private Pending pendingOf(final SessionState state, final AssembledUtterance utterance) {
        return new Pending(utterance, state.lastFoldedEnvelope);
    }

    private void emitAssembled(final Pending pending) {
        final Envelope source = pending.source();
        if (source == null || source.context() == null) {
            return;
        }
        final AssembledUtterance utterance = pending.utterance();
        try {
            delegate.emit(
                    assembledResult(utterance, source.result()),
                    source.context().withAssembly(utterance));
            if (!SentenceAssembler.REASON_LATE_AFTER_GAP.equals(utterance.flushReason())) {
                count("assembled_lines_total", utterance.flushReason());
            }
        } catch (final RuntimeException ex) {
            // The fragments this line was folded from are already delivered; losing the
            // convenience line must not fail the session — but it IS a delivery gap, so
            // it is metered, not silent.
            count("assembled_line_failures_total", utterance.flushReason());
            log.warn(
                    "assembled line emit failed sessionId={} reason={} error={}",
                    source.context().sessionId(),
                    utterance.flushReason(),
                    ex.getClass().getSimpleName());
        }
    }

    private void count(final String name, final String tag) {
        if (meters != null) {
            meters.counter(METRIC_PREFIX + name, "reason", tag).increment();
        }
    }

    /**
     * The assembled line as a transcript result: the text is the folded text, the model
     * metadata is carried over from the chunk it closed on, and {@code elapsedMs} is
     * dropped because no inference produced this line — reporting one would be a lie.
     */
    private static TranscriptResult assembledResult(
            final AssembledUtterance utterance, final TranscriptResult source) {
        return new TranscriptResult(
                utterance.text(),
                source == null ? null : source.language(),
                source == null ? null : source.languageProbability(),
                utterance.speechDurationMs() / 1000.0d,
                null,
                source == null ? null : source.model(),
                source == null ? null : source.computeType(),
                source == null ? null : source.device(),
                null);
    }

    /**
     * Stable identifier of the committed chunk a fragment came from — the gateway-side
     * window/chunk pair a client also sees on the event, NOT the downstream Redis record
     * id (which is assigned later). This is the namespace {@code sourceEventIds} uses.
     */
    private static String eventIdOf(final DirectSttTranscriptResultContext context) {
        // Window sequences restart per transport, so the transport has to be part of the
        // identity or a fallback's "0:0" would be indistinguishable from the socket's.
        return context.transport().name() + ":" + context.windowSeq() + ":" + context.chunkSeq();
    }
}
