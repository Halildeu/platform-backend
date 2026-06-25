package com.example.transcript.directstt;

import com.example.transcript.directstt.DirectSttTranscriptResultEvent.InvalidDirectSttTranscriptResultException;
import com.example.transcript.service.TranscriptSegmentService;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Validates direct-STT stream fields and routes accepted transcript drafts into
 * transcript-service storage. Logs are owned by the caller and must stay
 * metadata-only.
 */
@Service
public class DirectSttTranscriptResultHandler {

    private final TranscriptSegmentService segmentService;

    public DirectSttTranscriptResultHandler(TranscriptSegmentService segmentService) {
        this.segmentService = segmentService;
    }

    public HandleOutcome handle(Map<String, String> fields, String entryId) {
        try {
            DirectSttTranscriptResultEvent event = DirectSttTranscriptResultEvent.parse(fields, entryId);
            segmentService.upsertDirectSttDraft(event);
            return HandleOutcome.processed();
        } catch (InvalidDirectSttTranscriptResultException ex) {
            return HandleOutcome.invalid(ex.getMessage());
        }
    }

    public record HandleOutcome(Result result, String reason) {
        public static HandleOutcome processed() {
            return new HandleOutcome(Result.PROCESSED, null);
        }

        public static HandleOutcome invalid(String reason) {
            return new HandleOutcome(Result.INVALID, reason);
        }
    }

    public enum Result {
        PROCESSED,
        INVALID
    }
}
