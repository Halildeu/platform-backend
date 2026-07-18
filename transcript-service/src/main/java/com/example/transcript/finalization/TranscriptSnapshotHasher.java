package com.example.transcript.finalization;

import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** Validates and hashes the complete ordered canonical transcript projection. */
@Component
public class TranscriptSnapshotHasher {

    public Snapshot machineSnapshot(List<TranscriptSegment> segments) {
        return snapshot(segments, SnapshotMode.MACHINE);
    }

    public Snapshot editorialSnapshot(List<TranscriptSegment> segments) {
        return snapshot(segments, SnapshotMode.EDITORIAL);
    }

    private Snapshot snapshot(List<TranscriptSegment> segments, SnapshotMode mode) {
        if (segments.isEmpty()) {
            throw new InvalidSnapshotException("NO_SEGMENTS");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (TranscriptSegment segment : segments) {
                String selectedText = selectedText(segment, mode);
                update(digest, text(segment.getId()));
                update(digest, segment.getStatus().name());
                update(digest, selectedText);
                update(digest, text(segment.getSpeakerId()));
                update(digest, text(segment.getStartTime()));
                update(digest, text(segment.getEndTime()));
                update(digest, segment.getSourceSystem());
                update(digest, segment.getSourceSessionId());
                update(digest, text(segment.getSourceWindowSeq()));
                update(digest, text(segment.getSourceFirstChunkSeq()));
                update(digest, text(segment.getSourceLastChunkSeq()));
                update(digest, text(segment.getSourceChunkSeq()));
            }
            return new Snapshot(segments.size(), HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String selectedText(TranscriptSegment segment, SnapshotMode mode) {
        TranscriptSegmentStatus status = segment.getStatus();
        if (status == null) {
            throw invalid("STATUS_MISSING");
        }
        return switch (status) {
            case DRAFT -> {
                if (mode == SnapshotMode.EDITORIAL) {
                    throw invalid("DRAFT_NOT_EDITORIAL_FINAL");
                }
                if (!hasText(segment.getTextDraft()) || hasText(segment.getTextFinal())) {
                    throw invalid("DRAFT_TEXT_INVALID");
                }
                yield segment.getTextDraft();
            }
            case FINALIZED -> {
                if (!hasText(segment.getTextFinal())) {
                    throw invalid("FINAL_TEXT_MISSING");
                }
                yield segment.getTextFinal();
            }
            case REDACTED -> {
                if (hasText(segment.getTextDraft()) || hasText(segment.getTextFinal())) {
                    throw invalid("REDACTED_TEXT_RETAINED");
                }
                yield null;
            }
        };
    }

    private InvalidSnapshotException invalid(String reason) {
        return new InvalidSnapshotException(reason);
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private enum SnapshotMode { MACHINE, EDITORIAL }

    public record Snapshot(int segmentCount, String sha256) { }

    public static class InvalidSnapshotException extends IllegalStateException {
        public InvalidSnapshotException(String reason) { super(reason); }
    }
}
