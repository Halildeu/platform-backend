package com.example.audiogateway.service;

import com.example.audiogateway.service.AudioChunkDispatcher.ChunkDispatchCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionFinishCommand;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session-scoped, memory-only PCM16 window aggregator for Faz 24 issue #231.
 *
 * <p>All methods are synchronized because chunk admission and finish can arrive on different
 * request threads. Raw PCM is bounded by {@code maxBufferedSessions * windowSeconds} and is
 * removed after a complete window, terminal flush, or shutdown. Nothing is written to Redis
 * or disk.
 */
final class DirectSttAudioWindowAggregator {

    private static final int PCM16_BYTES_PER_SAMPLE = 2;

    private final int windowSeconds;
    private final int maxBufferedSessions;
    private final Map<String, SessionBuffer> sessions = new HashMap<>();

    DirectSttAudioWindowAggregator(final int windowSeconds, final int maxBufferedSessions) {
        this.windowSeconds = windowSeconds;
        this.maxBufferedSessions = maxBufferedSessions;
    }

    synchronized AppendResult append(final ChunkDispatchCommand cmd, final byte[] pcm16) {
        SessionBuffer buffer = sessions.get(cmd.sessionId());
        if (buffer == null) {
            if (sessions.size() >= maxBufferedSessions) {
                return new AppendResult(List.of(), true);
            }
            buffer = new SessionBuffer(cmd, targetBytes(cmd));
            sessions.put(cmd.sessionId(), buffer);
        } else {
            buffer.requireCompatible(cmd);
        }
        return new AppendResult(buffer.append(cmd, pcm16), false);
    }

    synchronized Optional<AudioWindow> finish(final SessionFinishCommand cmd) {
        final SessionBuffer buffer = sessions.get(cmd.sessionId());
        if (buffer == null) {
            return Optional.empty();
        }
        if (!buffer.isOwnedBy(cmd.tenantId(), cmd.userId())) {
            return Optional.empty();
        }
        sessions.remove(cmd.sessionId());
        return buffer.flushTail();
    }

    synchronized int activeSessions() {
        return sessions.size();
    }

    synchronized long bufferedBytes() {
        return sessions.values().stream().mapToLong(SessionBuffer::size).sum();
    }

    synchronized void discardAll() {
        sessions.values().forEach(SessionBuffer::discard);
        sessions.clear();
    }

    private int targetBytes(final ChunkDispatchCommand cmd) {
        final long bytes = (long) windowSeconds
                * cmd.sampleRateHz()
                * cmd.channels()
                * PCM16_BYTES_PER_SAMPLE;
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("PCM16 aggregation window byte size is out of range");
        }
        return (int) bytes;
    }

    record AppendResult(List<AudioWindow> windows, boolean capacityExceeded) {
        AppendResult {
            windows = List.copyOf(windows);
        }
    }

    record AudioWindow(
            byte[] audio,
            String sessionId,
            Long tenantId,
            Long userId,
            long windowSeq,
            long firstChunkSeq,
            long lastChunkSeq,
            long startedAtMs,
            String meetingId,
            String deviceId,
            String language,
            int sampleRateHz,
            int channels,
            String correlationId,
            String sha256,
            int durationMs
    ) {
    }

    private static final class SessionBuffer {
        private final String sessionId;
        private final Long tenantId;
        private final Long userId;
        private final String meetingId;
        private final String deviceId;
        private final String language;
        private final int sampleRateHz;
        private final int channels;
        private final int targetBytes;

        private byte[] data = new byte[0];
        private int size;
        private long nextWindowSeq;
        private long firstChunkSeq;
        private long lastChunkSeq;
        private long startedAtMs;
        private String correlationId;

        SessionBuffer(final ChunkDispatchCommand cmd, final int targetBytes) {
            this.sessionId = cmd.sessionId();
            this.tenantId = cmd.tenantId();
            this.userId = cmd.userId();
            this.meetingId = cmd.meetingId();
            this.deviceId = cmd.deviceId();
            this.language = cmd.language();
            this.sampleRateHz = cmd.sampleRateHz();
            this.channels = cmd.channels();
            this.targetBytes = targetBytes;
        }

        List<AudioWindow> append(final ChunkDispatchCommand cmd, final byte[] pcm16) {
            final List<AudioWindow> complete = new ArrayList<>();
            int sourceOffset = 0;
            while (sourceOffset < pcm16.length) {
                if (size == 0) {
                    firstChunkSeq = cmd.chunkSeq();
                    startedAtMs = cmd.chunkStartedAtMs();
                }
                lastChunkSeq = cmd.chunkSeq();
                correlationId = cmd.correlationId();

                final int copyLength = Math.min(targetBytes - size, pcm16.length - sourceOffset);
                ensureCapacity(size + copyLength);
                System.arraycopy(pcm16, sourceOffset, data, size, copyLength);
                size += copyLength;
                sourceOffset += copyLength;

                if (size == targetBytes) {
                    complete.add(takeWindow());
                }
            }
            return complete;
        }

        Optional<AudioWindow> flushTail() {
            if (size == 0) {
                discard();
                return Optional.empty();
            }
            return Optional.of(takeWindow());
        }

        void requireCompatible(final ChunkDispatchCommand cmd) {
            if (!sessionId.equals(cmd.sessionId())
                    || !tenantId.equals(cmd.tenantId())
                    || !userId.equals(cmd.userId())
                    || !meetingId.equals(cmd.meetingId())
                    || !deviceId.equals(cmd.deviceId())
                    || !language.equals(cmd.language())
                    || sampleRateHz != cmd.sampleRateHz()
                    || channels != cmd.channels()) {
                throw new IllegalArgumentException(
                        "Direct-STT aggregation metadata changed inside a session");
            }
        }

        boolean isOwnedBy(final Long expectedTenantId, final Long expectedUserId) {
            return tenantId.equals(expectedTenantId) && userId.equals(expectedUserId);
        }

        int size() {
            return size;
        }

        void discard() {
            Arrays.fill(data, (byte) 0);
            data = new byte[0];
            size = 0;
        }

        private AudioWindow takeWindow() {
            final byte[] audio = Arrays.copyOf(data, size);
            final AudioWindow window = new AudioWindow(
                    audio,
                    sessionId,
                    tenantId,
                    userId,
                    nextWindowSeq++,
                    firstChunkSeq,
                    lastChunkSeq,
                    startedAtMs,
                    meetingId,
                    deviceId,
                    language,
                    sampleRateHz,
                    channels,
                    correlationId,
                    sha256(audio),
                    durationMs(audio.length, sampleRateHz, channels));
            Arrays.fill(data, 0, size, (byte) 0);
            size = 0;
            return window;
        }

        private void ensureCapacity(final int required) {
            if (required <= data.length) {
                return;
            }
            int capacity = Math.max(4096, data.length);
            while (capacity < required) {
                capacity = Math.min(targetBytes, Math.multiplyExact(capacity, 2));
            }
            data = Arrays.copyOf(data, capacity);
        }
    }

    private static int durationMs(final int byteLength, final int sampleRateHz, final int channels) {
        final long bytesPerSecond = (long) sampleRateHz * channels * PCM16_BYTES_PER_SAMPLE;
        return (int) Math.max(1L, (byteLength * 1000L) / bytesPerSecond);
    }

    private static String sha256(final byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
