package com.example.audiogateway.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;

/**
 * Bounded PCM history used to materialize the exact source range named by a live STT final.
 *
 * <p>The producer reports absolute sample coordinates taken before final inference starts.
 * Keeping a bounded history, instead of consuming every frame visible when the final arrives,
 * preserves correct metadata when inference overlaps later audio or deliberately reuses a tail.
 * Raw PCM remains connection-local, bounded by configuration, and is never logged or persisted.
 */
final class LiveTranscriptWindowAccumulator {

    private static final int PCM16_SAMPLE_BYTES = Short.BYTES;

    private final int maxHistoryBytes;
    private final Deque<FrameRange> frames = new ArrayDeque<>();
    private long nextSample;
    private int retainedBytes;
    private int sampleRateHz;
    private int channels;
    private long lastWindowSeq = -1L;
    private long lastSourceEndSample = -1L;

    LiveTranscriptWindowAccumulator(final int maxHistoryBytes) {
        if (maxHistoryBytes <= 0) {
            throw new IllegalArgumentException("live STT source history bound must be positive");
        }
        this.maxHistoryBytes = maxHistoryBytes;
    }

    synchronized void append(
            final LiveAudioStreamFrame frame,
            final int currentSampleRateHz,
            final int currentChannels) {
        if (currentSampleRateHz <= 0 || currentChannels <= 0) {
            throw new IllegalArgumentException("live STT PCM format is invalid");
        }
        if (sampleRateHz == 0) {
            sampleRateHz = currentSampleRateHz;
            channels = currentChannels;
        } else if (sampleRateHz != currentSampleRateHz || channels != currentChannels) {
            throw new IllegalStateException("live STT PCM format changed within one stream");
        }

        final byte[] pcm16 = frame.pcm16();
        final long sampleCount = pcm16.length / PCM16_SAMPLE_BYTES;
        final long endSample = Math.addExact(nextSample, sampleCount);
        frames.addLast(new FrameRange(
                frame.chunkSeq(), frame.capturedAtMs(), nextSample, endSample, pcm16));
        nextSample = endSample;
        retainedBytes = Math.addExact(retainedBytes, pcm16.length);
        pruneHistory();
    }

    synchronized Window take(
            final long windowSeq,
            final long sourceStartSample,
            final long sourceEndSample) {
        if (windowSeq <= lastWindowSeq) {
            throw new IllegalStateException("live STT final sequence is not strictly increasing");
        }
        if (sourceEndSample <= lastSourceEndSample) {
            throw new IllegalStateException("live STT final source range made no forward progress");
        }
        if (sourceStartSample < 0L || sourceEndSample <= sourceStartSample
                || sourceEndSample > nextSample) {
            throw new IllegalStateException("live STT final source range is outside accepted audio");
        }
        if (frames.isEmpty() || sourceStartSample < frames.getFirst().startSample()) {
            throw new IllegalStateException("live STT final source range exceeded bounded history");
        }

        final MessageDigest digest = newDigest();
        long cursor = sourceStartSample;
        long firstChunkSeq = -1L;
        long lastChunkSeq = -1L;
        long startedAtMs = -1L;
        long endedAtMs = -1L;
        int byteLength = 0;

        for (FrameRange frame : frames) {
            final long overlapStart = Math.max(sourceStartSample, frame.startSample());
            final long overlapEnd = Math.min(sourceEndSample, frame.endSample());
            if (overlapStart >= overlapEnd) {
                continue;
            }
            if (overlapStart != cursor) {
                throw new IllegalStateException("live STT final source range has a history gap");
            }

            final int offsetBytes = Math.toIntExact(
                    Math.multiplyExact(overlapStart - frame.startSample(), PCM16_SAMPLE_BYTES));
            final int lengthBytes = Math.toIntExact(
                    Math.multiplyExact(overlapEnd - overlapStart, PCM16_SAMPLE_BYTES));
            digest.update(frame.pcm16(), offsetBytes, lengthBytes);
            byteLength = Math.addExact(byteLength, lengthBytes);

            if (firstChunkSeq < 0L) {
                firstChunkSeq = frame.chunkSeq();
                startedAtMs = timestampAt(frame, overlapStart);
            }
            lastChunkSeq = frame.chunkSeq();
            endedAtMs = timestampAt(frame, overlapEnd);
            cursor = overlapEnd;
            if (cursor == sourceEndSample) {
                break;
            }
        }

        if (cursor != sourceEndSample || firstChunkSeq < 0L) {
            throw new IllegalStateException("live STT final source range is incomplete");
        }
        final int durationMs = Math.toIntExact(Math.round(
                (sourceEndSample - sourceStartSample) * 1000.0d
                        / ((long) sampleRateHz * channels)));
        lastWindowSeq = windowSeq;
        lastSourceEndSample = sourceEndSample;
        return new Window(
                windowSeq,
                firstChunkSeq,
                lastChunkSeq,
                startedAtMs,
                endedAtMs,
                durationMs,
                byteLength,
                "sha256:" + HexFormat.of().formatHex(digest.digest()));
    }

    private long timestampAt(final FrameRange frame, final long absoluteSample) {
        return frame.capturedAtMs() + Math.round(
                (absoluteSample - frame.startSample()) * 1000.0d
                        / ((long) sampleRateHz * channels));
    }

    private void pruneHistory() {
        while (retainedBytes > maxHistoryBytes && frames.size() > 1) {
            retainedBytes -= frames.removeFirst().pcm16().length;
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record FrameRange(
            long chunkSeq,
            long capturedAtMs,
            long startSample,
            long endSample,
            byte[] pcm16) {
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
