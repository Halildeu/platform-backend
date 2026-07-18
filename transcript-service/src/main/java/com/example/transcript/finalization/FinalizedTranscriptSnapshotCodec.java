package com.example.transcript.finalization;

import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSegment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Captures and verifies the immutable text projection selected by one finalization occurrence. */
@Component
public class FinalizedTranscriptSnapshotCodec {

    private static final TypeReference<List<StoredSegment>> SEGMENT_LIST = new TypeReference<>() { };

    private final TranscriptSnapshotHasher snapshotHasher;
    private final ObjectMapper objectMapper;

    public FinalizedTranscriptSnapshotCodec(
            TranscriptSnapshotHasher snapshotHasher, ObjectMapper objectMapper) {
        this.snapshotHasher = snapshotHasher;
        this.objectMapper = objectMapper;
    }

    public StoredSnapshot captureMachine(List<TranscriptSegment> segments) {
        TranscriptSnapshotHasher.Snapshot sourceSnapshot = snapshotHasher.machineSnapshot(segments);
        return capture(segments, sourceSnapshot, SnapshotMode.MACHINE);
    }

    public StoredSnapshot captureEditorial(List<TranscriptSegment> segments) {
        TranscriptSnapshotHasher.Snapshot sourceSnapshot = snapshotHasher.editorialSnapshot(segments);
        return capture(segments, sourceSnapshot, SnapshotMode.EDITORIAL);
    }

    public boolean hasPersistedProjection(TranscriptFinalization finalization) {
        int present = 0;
        present += finalization.getCanonicalTranscript() == null ? 0 : 1;
        present += finalization.getCanonicalTranscriptSha256() == null ? 0 : 1;
        present += finalization.getCanonicalSegments() == null ? 0 : 1;
        present += finalization.getCanonicalProjectionSha256() == null ? 0 : 1;
        if (present == 0) {
            return false;
        }
        if (present != 4) {
            throw new InvalidStoredSnapshotException("CANONICAL_PROJECTION_INCOMPLETE");
        }
        return true;
    }

    public StoredSnapshot restore(TranscriptFinalization finalization) {
        if (!hasPersistedProjection(finalization)) {
            throw new InvalidStoredSnapshotException("CANONICAL_PROJECTION_LEGACY");
        }
        try {
            List<StoredSegment> decoded = objectMapper.readValue(
                    finalization.getCanonicalSegments(), SEGMENT_LIST);
            if (decoded == null) {
                throw new InvalidStoredSnapshotException("CANONICAL_PROJECTION_INVALID");
            }
            validateSegments(decoded);
            List<StoredSegment> segments = List.copyOf(decoded);
            if (segments.size() != finalization.getSegmentCount()) {
                throw new InvalidStoredSnapshotException("CANONICAL_SEGMENT_COUNT_MISMATCH");
            }

            String normalizedProjection = objectMapper.writeValueAsString(segments);
            requireHash(normalizedProjection, finalization.getCanonicalProjectionSha256(),
                    "CANONICAL_PROJECTION_HASH_MISMATCH");
            String rebuiltTranscript = transcript(segments);
            if (!constantTimeEquals(rebuiltTranscript, finalization.getCanonicalTranscript())) {
                throw new InvalidStoredSnapshotException("CANONICAL_TRANSCRIPT_MISMATCH");
            }
            requireHash(rebuiltTranscript, finalization.getCanonicalTranscriptSha256(),
                    "CANONICAL_TRANSCRIPT_HASH_MISMATCH");
            return new StoredSnapshot(
                    segments.size(),
                    finalization.getSnapshotSha256(),
                    rebuiltTranscript,
                    finalization.getCanonicalTranscriptSha256(),
                    normalizedProjection,
                    finalization.getCanonicalProjectionSha256(),
                    segments);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new InvalidStoredSnapshotException("CANONICAL_PROJECTION_INVALID", ex);
        }
    }

    private StoredSnapshot capture(
            List<TranscriptSegment> sourceSegments,
            TranscriptSnapshotHasher.Snapshot sourceSnapshot,
            SnapshotMode mode) {
        List<StoredSegment> storedSegments = sourceSegments.stream()
                .map(segment -> storedSegment(segment, mode))
                .toList();
        validateSegments(storedSegments);
        try {
            String projection = objectMapper.writeValueAsString(storedSegments);
            String transcript = transcript(storedSegments);
            return new StoredSnapshot(
                    storedSegments.size(),
                    sourceSnapshot.sha256(),
                    transcript,
                    sha256(transcript),
                    projection,
                    sha256(projection),
                    storedSegments);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Canonical projection serialization failed", ex);
        }
    }

    private StoredSegment storedSegment(TranscriptSegment segment, SnapshotMode mode) {
        String text = switch (segment.getStatus()) {
            case DRAFT -> mode == SnapshotMode.MACHINE ? segment.getTextDraft() : null;
            case FINALIZED -> segment.getTextFinal();
            case REDACTED -> null;
        };
        return new StoredSegment(text, segment.getStartTime(), segment.getEndTime());
    }

    private void validateSegments(List<StoredSegment> segments) {
        if (segments.isEmpty()) {
            throw new InvalidStoredSnapshotException("CANONICAL_SEGMENTS_EMPTY");
        }
        for (StoredSegment segment : segments) {
            if (segment == null
                    || segment.start() == null
                    || !Double.isFinite(segment.start())
                    || segment.start() < 0
                    || (segment.end() != null
                        && (!Double.isFinite(segment.end()) || segment.end() < segment.start()))) {
                throw new InvalidStoredSnapshotException("CANONICAL_SEGMENT_TIMING_INVALID");
            }
        }
    }

    private String transcript(List<StoredSegment> segments) {
        return segments.stream()
                .map(StoredSegment::text)
                .filter(value -> value != null)
                .collect(Collectors.joining("\n"));
    }

    private void requireHash(String value, String expected, String reason) {
        if (!constantTimeEquals(sha256(value), expected)) {
            throw new InvalidStoredSnapshotException(reason);
        }
    }

    private boolean constantTimeEquals(String actual, String expected) {
        return actual != null && expected != null && MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private enum SnapshotMode { MACHINE, EDITORIAL }

    public record StoredSegment(String text, Double start, Double end) { }

    public record StoredSnapshot(
            int segmentCount,
            String sourceSnapshotSha256,
            String transcript,
            String transcriptSha256,
            String canonicalSegments,
            String canonicalProjectionSha256,
            List<StoredSegment> segments) { }

    public static class InvalidStoredSnapshotException extends IllegalStateException {
        public InvalidStoredSnapshotException(String reason) {
            super(reason);
        }

        public InvalidStoredSnapshotException(String reason, Throwable cause) {
            super(reason, cause);
        }
    }
}
