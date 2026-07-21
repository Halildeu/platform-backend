package com.example.audiogateway.service;

import com.example.audiogateway.dto.TranscriptResult;
import io.micrometer.core.instrument.MeterRegistry;
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
 * <h2>Additive, never destructive</h2>
 * The raw chunk is forwarded to the delegate first and unchanged, exactly as before.
 * The assembled line is emitted <i>in addition</i>, carrying
 * {@link DirectSttTranscriptResultContext.Assembly} so a consumer can tell the two apart
 * and trace a line back to its fragments. Nothing is replaced or dropped, so a client
 * that has not been taught about the new status keeps working — which is why the feature
 * is also default-off until both clients render it.
 *
 * <h2>Emission failures are contained</h2>
 * An assembled emission that throws is logged and swallowed: the raw chunk has already
 * been durably committed, and a readability enhancement must never turn a delivered
 * transcript into a failed one.
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

    private static final class SessionState {
        private final SentenceAssembler assembler;
        private DirectSttTranscriptResultContext lastContext;
        private TranscriptResult lastResult;
        private long touchedAtMs;

        private SessionState(final SentenceAssembler assembler, final long nowMs) {
            this.assembler = assembler;
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

        try {
            final SessionState state =
                    sessions.computeIfAbsent(
                            context.sessionId(),
                            id -> new SessionState(new SentenceAssembler(policy), clock.getAsLong()));
            final List<AssembledUtterance> closed;
            synchronized (state) {
                state.lastContext = context;
                state.lastResult = result;
                state.touchedAtMs = clock.getAsLong();
                closed =
                        state.assembler.offer(
                                new SentenceAssembler.Fragment(
                                        eventIdOf(context),
                                        result == null ? null : result.text(),
                                        context.windowStartedAtMs(),
                                        context.windowEndedAtMs(),
                                        context.audioDurationMs()));
            }
            closed.forEach(utterance -> emitAssembled(utterance, context, result));
        } catch (final RuntimeException ex) {
            log.warn(
                    "sentence assembly skipped sessionId={} reason={}",
                    context.sessionId(),
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * Close a session's buffer and emit whatever is left — the recording ended.
     *
     * <p>Safe to call for an unknown session and safe to call twice; the trailing line
     * is emitted at most once.
     */
    public void closeSession(final String sessionId) {
        if (sessionId == null) {
            return;
        }
        final SessionState state = sessions.remove(sessionId);
        if (state == null) {
            return;
        }
        flush(state, state.assembler.closeSession().stream().toList());
    }

    /**
     * Close lines whose speaker has stopped, and drop long-idle session state.
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
                    final List<AssembledUtterance> closed;
                    synchronized (state) {
                        closed = state.assembler.flushIfIdle(now).stream().toList();
                    }
                    flush(state, closed);
                    if (!state.assembler.hasBufferedText()
                            && now - state.touchedAtMs >= SESSION_EVICT_AFTER_MS) {
                        sessions.remove(sessionId, state);
                    }
                });
    }

    /** Test/observability helper — sessions currently holding assembler state. */
    int trackedSessions() {
        return sessions.size();
    }

    private void flush(final SessionState state, final List<AssembledUtterance> closed) {
        if (closed.isEmpty() || state.lastContext == null) {
            return;
        }
        closed.forEach(utterance -> emitAssembled(utterance, state.lastContext, state.lastResult));
    }

    private void emitAssembled(
            final AssembledUtterance utterance,
            final DirectSttTranscriptResultContext context,
            final TranscriptResult source) {
        try {
            delegate.emit(assembledResult(utterance, source), context.withAssembly(utterance));
            if (meters != null) {
                meters.counter(METRIC_PREFIX + "assembled_lines_total", "reason", utterance.flushReason())
                        .increment();
            }
        } catch (final RuntimeException ex) {
            // The fragments this line was folded from are already delivered; losing the
            // convenience line must not fail the session.
            log.warn(
                    "assembled line emit failed sessionId={} reason={} error={}",
                    context.sessionId(),
                    utterance.flushReason(),
                    ex.getClass().getSimpleName());
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
     * Stable identifier of the committed chunk a fragment came from. The Redis record id
     * is assigned downstream, so the gateway-side identity is the window/chunk pair —
     * which is what a client sees on the event as {@code windowSeq}/{@code chunkSeq}.
     */
    private static String eventIdOf(final DirectSttTranscriptResultContext context) {
        return context.windowSeq() + ":" + context.chunkSeq();
    }
}
