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
import org.springframework.scheduling.annotation.Scheduled;

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
 * {@link SentenceAssembler}. The assembler only ever sees fragments in source order.
 *
 * <h2>Additive, never destructive</h2>
 * The raw chunk is forwarded to the delegate first and unchanged, exactly as before.
 * The assembled line is emitted <i>in addition</i>, carrying
 * {@link DirectSttTranscriptResultContext.Assembly} so a consumer can tell the two apart
 * and trace a line back to its fragments. Nothing is replaced or dropped, so a client
 * that has not been taught about the new status keeps working — which is why the feature
 * is also default-off until both clients render it.
 *
 * <h2>One clock</h2>
 * Idle timing runs entirely on the gateway receipt clock ({@link #clock}). Source window
 * timestamps are provenance only — the contract allows them to be zero or arbitrary — so
 * they are never compared against wall-clock time.
 *
 * <h2>Per-session serialisation</h2>
 * All access to a session's reorder buffer and assembler is under that session's monitor,
 * and the map entry is created and removed atomically. A concurrent emit, sweep, and
 * close therefore cannot interleave on the same buffer or emit an assembled line stamped
 * with another fragment's metadata.
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

    private final class SessionState {
        private final ChunkReorderBuffer reorder =
                new ChunkReorderBuffer(REORDER_CAPACITY, REORDER_WINDOW_MS);
        private final SentenceAssembler assembler = new SentenceAssembler(policy);
        private DirectSttTranscriptResultContext lastContext;
        private TranscriptResult lastResult;
        private long touchedAtMs;

        private SessionState(final long nowMs) {
            this.touchedAtMs = nowMs;
        }

        /** An assembled line paired with the context/result that should carry it. */
        private record Pending(
                AssembledUtterance utterance,
                DirectSttTranscriptResultContext context,
                TranscriptResult result) {}
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

        final List<SessionState.Pending> toEmit;
        try {
            final SessionState state =
                    sessions.computeIfAbsent(context.sessionId(), id -> new SessionState(clock.getAsLong()));
            synchronized (state) {
                final long receiptMs = clock.getAsLong();
                state.lastContext = context;
                state.lastResult = result;
                state.touchedAtMs = receiptMs;
                final SentenceAssembler.Fragment fragment =
                        new SentenceAssembler.Fragment(
                                eventIdOf(context),
                                result == null ? null : result.text(),
                                context.windowStartedAtMs(),
                                context.windowEndedAtMs(),
                                context.audioDurationMs(),
                                receiptMs);
                final List<SentenceAssembler.Fragment> ordered =
                        reorderKeyOf(context) < 0
                                ? List.of(fragment)
                                : state.reorder.offer(reorderKeyOf(context), fragment);
                toEmit = foldAll(state, ordered);
            }
        } catch (final RuntimeException ex) {
            log.warn(
                    "sentence assembly skipped sessionId={} reason={}",
                    context.sessionId(),
                    ex.getClass().getSimpleName());
            return;
        }
        toEmit.forEach(this::emitAssembled);
    }

    /**
     * Close a session's buffers and emit whatever is left — the recording ended.
     *
     * <p>Safe to call for an unknown session and safe to call twice; the trailing line
     * is emitted at most once. Callers should invoke this only after the last outstanding
     * STT result for the session has reached the sink (WS {@code drained} / REST terminal
     * completion), so no fragment is stranded in the reorder buffer.
     */
    public void closeSession(final String sessionId) {
        if (sessionId == null) {
            return;
        }
        final SessionState state = sessions.remove(sessionId);
        if (state == null) {
            return;
        }
        final List<SessionState.Pending> toEmit;
        synchronized (state) {
            final List<SessionState.Pending> pending = new ArrayList<>(foldAll(state, state.reorder.flushAll()));
            state.assembler
                    .closeSession()
                    .ifPresent(u -> pending.add(pendingOf(state, u)));
            toEmit = pending;
        }
        toEmit.forEach(this::emitAssembled);
    }

    /**
     * Release stalled reorder gaps, close lines whose speaker has stopped, and drop
     * long-idle session state.
     *
     * <p>Without this a trailing line would wait for the next fragment — which never
     * comes once the speaker stops talking.
     */
    @Scheduled(
            fixedDelayString =
                    "${audio.gateway.direct-stt.sentence-assembly.sweep-interval-ms:1000}")
    public void sweep() {
        final long now = clock.getAsLong();
        sessions.forEach(
                (sessionId, state) -> {
                    final List<SessionState.Pending> toEmit;
                    final boolean evict;
                    synchronized (state) {
                        final List<SessionState.Pending> pending =
                                new ArrayList<>(foldAll(state, state.reorder.sweep(now)));
                        state.assembler
                                .flushIfIdle(now)
                                .ifPresent(u -> pending.add(pendingOf(state, u)));
                        toEmit = pending;
                        evict =
                                !state.assembler.hasBufferedText()
                                        && !state.reorder.hasPending()
                                        && now - state.touchedAtMs >= SESSION_EVICT_AFTER_MS;
                    }
                    toEmit.forEach(this::emitAssembled);
                    if (evict) {
                        sessions.remove(sessionId, state);
                    }
                });
    }

    /** Test/observability helper — sessions currently holding assembler state. */
    int trackedSessions() {
        return sessions.size();
    }

    /** Fold every ordered fragment through the assembler; must hold the session monitor. */
    private List<SessionState.Pending> foldAll(
            final SessionState state, final List<SentenceAssembler.Fragment> ordered) {
        if (ordered.isEmpty()) {
            return List.of();
        }
        final List<SessionState.Pending> pending = new ArrayList<>();
        for (final SentenceAssembler.Fragment fragment : ordered) {
            for (final AssembledUtterance utterance : state.assembler.offer(fragment)) {
                pending.add(pendingOf(state, utterance));
            }
        }
        return pending;
    }

    /** Snapshot the current session context/result for an assembled line, under the monitor. */
    private SessionState.Pending pendingOf(
            final SessionState state, final AssembledUtterance utterance) {
        return new SessionState.Pending(utterance, state.lastContext, state.lastResult);
    }

    private void emitAssembled(final SessionState.Pending pending) {
        final DirectSttTranscriptResultContext context = pending.context();
        if (context == null) {
            return;
        }
        final AssembledUtterance utterance = pending.utterance();
        try {
            delegate.emit(
                    assembledResult(utterance, pending.result()), context.withAssembly(utterance));
            count("assembled_lines_total", utterance.flushReason());
        } catch (final RuntimeException ex) {
            // The fragments this line was folded from are already delivered; losing the
            // convenience line must not fail the session — but it IS a delivery gap, so
            // it is metered, not silent.
            count("assembled_line_failures_total", utterance.flushReason());
            log.warn(
                    "assembled line emit failed sessionId={} reason={} error={}",
                    context.sessionId(),
                    utterance.flushReason(),
                    ex.getClass().getSimpleName());
        }
    }

    private void count(final String name, final String reason) {
        if (meters != null) {
            meters.counter(METRIC_PREFIX + name, "reason", reason).increment();
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
     * Source-order key for reordering. {@code windowSeq} is the gateway's own sequence of
     * committed windows; a negative value (unset) disables reordering for that fragment
     * so it passes straight through.
     */
    private static long reorderKeyOf(final DirectSttTranscriptResultContext context) {
        return context.windowSeq();
    }

    /**
     * Stable identifier of the committed chunk a fragment came from — the gateway-side
     * window/chunk pair a client also sees on the event, NOT the downstream Redis record
     * id (which is assigned later). This is the namespace {@code sourceEventIds} uses.
     */
    private static String eventIdOf(final DirectSttTranscriptResultContext context) {
        return context.windowSeq() + ":" + context.chunkSeq();
    }
}
