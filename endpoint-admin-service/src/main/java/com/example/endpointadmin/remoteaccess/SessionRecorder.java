package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;

/**
 * Faz 22.6 C-3 — the session recorder facade (ADR-0034 D3): ties the C-1 tamper-evident {@link
 * SessionRecordingChain} to a durable {@link RecordingSink} and the C-2 {@link RecordingAnchorSigner}, and
 * exposes the {@code recordingWriterAck} health signal the heartbeat enforces (the RECORDER guarantee — a
 * session may not stay ACTIVE if the recorder is unhealthy).
 *
 * <p><b>Fail-closed + latching</b> (ADR-0034 D3 mandatory fail-closed recording): each {@link #record} appends
 * to the in-process hash-chain AND writes the entry to the durable sink. If the sink write does NOT durably
 * commit, the recorder becomes permanently {@code unhealthy} — once recording is compromised the session can
 * no longer be trusted to be recorded, so it must die and stay dead (a transient sink blip must not silently
 * leave a gap in the trail). {@link #isHealthy} also re-checks the live sink writability + the chain integrity.
 */
public final class SessionRecorder {

    private final SessionRecordingChain chain = new SessionRecordingChain();
    private final RecordingSink sink;
    private final RecordingAnchorSigner anchorSigner;
    private volatile boolean recordingBroken = false; // latches true on the first failed durable write

    public SessionRecorder(RecordingSink sink, RecordingAnchorSigner anchorSigner) {
        if (sink == null || anchorSigner == null) {
            throw new IllegalArgumentException("sink + anchorSigner must be non-null");
        }
        this.sink = sink;
        this.anchorSigner = anchorSigner;
    }

    /**
     * Record a metadata event: append to the hash-chain + durably write it. Returns {@code true} iff the
     * write durably committed. On a sink failure the recorder LATCHES unhealthy — the in-process chain may be
     * one entry ahead of what the sink durably holds, but that divergence is moot because the session is then
     * killed (recordingWriterAck=false) and the partial chain is never anchored (C-2) or trusted again.
     */
    public synchronized boolean record(RecordKind kind, String contentHash, long timestampMillis) {
        if (recordingBroken) {
            return false; // already compromised — no further records are trustworthy
        }
        SessionRecordingChain.Entry entry;
        try {
            entry = chain.append(kind, contentHash, timestampMillis);
        } catch (RuntimeException e) {
            recordingBroken = true; // a malformed record we can't even chain → fail-closed
            return false;
        }
        try {
            sink.append(entry);
            return true;
        } catch (RecordingSink.RecordingSinkException | RuntimeException e) {
            // the entry was chained in-process but did NOT durably land → the trail would have a gap.
            // Latch unhealthy; the heartbeat will kill the session on the next sample (recordingWriterAck=false).
            recordingBroken = true;
            return false;
        }
    }

    /**
     * The {@code recordingWriterAck} signal: the recorder is healthy iff recording has never broken AND the
     * sink is currently writable AND the in-process chain is intact. Fed to the RECORDER precondition.
     */
    public synchronized boolean isHealthy() {
        return !recordingBroken && sink.isWritable() && chain.verifyIntegrity();
    }

    /** Produce a signed out-of-band anchor over the current chain state (C-2). */
    public synchronized RecordingAnchor anchor(long timestampMillis) {
        return anchorSigner.anchor(chain, timestampMillis);
    }

    public synchronized String headHash() {
        return chain.headHash();
    }

    public synchronized int recordedCount() {
        return chain.size();
    }
}
