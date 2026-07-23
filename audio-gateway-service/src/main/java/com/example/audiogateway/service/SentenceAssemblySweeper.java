package com.example.audiogateway.service;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduling seam for {@link SentenceAssemblingSink#sweep()} (Faz 24 — transcript
 * readability).
 *
 * <p>The assembling sink is built inside the sink-chain factory rather than being a bean
 * of its own — that is what keeps exactly one {@code @Primary} sink no matter which
 * feature flags are on. A non-bean's {@code @Scheduled} method is never discovered, so
 * the periodic work needs a bean that owns it: this one.
 *
 * <p>It takes the chain head and sweeps it only when assembly is actually the outermost
 * layer, so a configuration change that removes assembly degrades to a no-op rather than
 * a failure.
 */
public final class SentenceAssemblySweeper {

    private final DirectSttTranscriptResultSink chainHead;

    public SentenceAssemblySweeper(final DirectSttTranscriptResultSink chainHead) {
        this.chainHead = chainHead;
    }

    /**
     * Close lines whose speaker stopped and release stalled reorder gaps.
     *
     * <p>Runs often relative to the idle bound: a trailing line should appear shortly
     * after the speaker stops, not a bound later.
     */
    @Scheduled(
            fixedDelayString =
                    "${audio.gateway.direct-stt.sentence-assembly.sweep-interval-ms:1000}")
    public void sweep() {
        if (chainHead instanceof SentenceAssemblingSink sink) {
            sink.sweep();
        }
    }

    /**
     * Close a session's buffers immediately — the recording ended.
     *
     * <p>Exposed here so the session-lifecycle seam has one place to call without
     * reaching into the chain itself.
     */
    public void closeSession(final String sessionId) {
        if (chainHead instanceof SentenceAssemblingSink sink) {
            sink.closeSession(sessionId);
        }
    }
}
