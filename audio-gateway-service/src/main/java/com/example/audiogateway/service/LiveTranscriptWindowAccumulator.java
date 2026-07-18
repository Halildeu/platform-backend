package com.example.audiogateway.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content-free metadata accumulator for the PCM frames represented by one live STT final.
 *
 * <p>The raw PCM is fed directly into the digest and is never retained here. A snapshot is
 * consumed atomically when the upstream emits a final result, so later audio belongs to the
 * next source window even while the previous result is being persisted.
 */
final class LiveTranscriptWindowAccumulator {

    private MessageDigest digest = newDigest();
    private boolean pending;
    private long firstChunkSeq;
    private long lastChunkSeq;
    private long startedAtMs;
    private long endedAtMs;
    private int durationMs;
    private int byteLength;

    synchronized void append(
            final LiveAudioStreamFrame frame,
            final int sampleRateHz,
            final int channels) {
        final int frameDurationMs = frame.durationMs(sampleRateHz, channels);
        final long frameEndedAtMs = Math.addExact(frame.capturedAtMs(), frameDurationMs);
        if (!pending) {
            pending = true;
            firstChunkSeq = frame.chunkSeq();
            startedAtMs = frame.capturedAtMs();
        }
        lastChunkSeq = frame.chunkSeq();
        endedAtMs = Math.max(endedAtMs, frameEndedAtMs);
        durationMs = Math.addExact(durationMs, frameDurationMs);
        byteLength = Math.addExact(byteLength, frame.pcm16().length);
        digest.update(frame.pcm16());
    }

    synchronized Window take(final long windowSeq) {
        if (!pending) {
            throw new IllegalStateException("live STT final has no accepted audio window");
        }
        final Window window = new Window(
                windowSeq,
                firstChunkSeq,
                lastChunkSeq,
                startedAtMs,
                endedAtMs,
                durationMs,
                byteLength,
                "sha256:" + HexFormat.of().formatHex(digest.digest()));
        digest = newDigest();
        pending = false;
        firstChunkSeq = 0L;
        lastChunkSeq = 0L;
        startedAtMs = 0L;
        endedAtMs = 0L;
        durationMs = 0;
        byteLength = 0;
        return window;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Window(
            long windowSeq,
            long firstChunkSeq,
            long lastChunkSeq,
            long startedAtMs,
            long endedAtMs,
            int durationMs,
            int byteLength,
            String sha256) {
    }
}
