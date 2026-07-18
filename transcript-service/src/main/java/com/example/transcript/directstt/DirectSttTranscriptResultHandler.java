package com.example.transcript.directstt;

import com.example.transcript.directstt.DirectSttTranscriptResultEvent.InvalidDirectSttTranscriptResultException;
import com.example.transcript.directstt.DirectSttTranscriptIngestionService.SessionAssociationConflictException;
import com.example.transcript.directstt.DirectSttTranscriptIngestionService.SessionAssociationNotResolvedException;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Validates direct-STT stream fields and routes accepted transcript drafts into
 * transcript-service storage. Logs are owned by the caller and must stay
 * metadata-only.
 */
@Service
public class DirectSttTranscriptResultHandler {

    private final TranscriptSessionAssociationService associationService;
    private final DirectSttTranscriptIngestionService ingestionService;

    public DirectSttTranscriptResultHandler(
            TranscriptSessionAssociationService associationService,
            DirectSttTranscriptIngestionService ingestionService) {
        this.associationService = associationService;
        this.ingestionService = ingestionService;
    }

    public HandleOutcome handle(Map<String, String> fields, String entryId) {
        try {
            DirectSttTranscriptResultEvent event = DirectSttTranscriptResultEvent.parse(fields, entryId);
            var association = associationService.resolve(event);
            return switch (association.result()) {
                case RESOLVED -> {
                    ingestionService.upsert(event, association.sessionId());
                    yield HandleOutcome.processed();
                }
                case PENDING -> HandleOutcome.pending(association.reasonCode());
                case DEAD -> HandleOutcome.dead(association.reasonCode());
            };
        } catch (InvalidDirectSttTranscriptResultException ex) {
            return HandleOutcome.invalid(ex.getMessage());
        } catch (SessionAssociationConflictException
                 | SessionAssociationNotResolvedException ex) {
            return HandleOutcome.dead(ex.getClass().getSimpleName());
        }
    }

    public record HandleOutcome(Result result, String reason) {
        public static HandleOutcome processed() {
            return new HandleOutcome(Result.PROCESSED, null);
        }

        public static HandleOutcome invalid(String reason) {
            return new HandleOutcome(Result.INVALID, reason);
        }

        public static HandleOutcome pending(String reason) {
            return new HandleOutcome(Result.PENDING, reason);
        }

        public static HandleOutcome dead(String reason) {
            return new HandleOutcome(Result.DEAD, reason);
        }
    }

    public enum Result {
        PROCESSED,
        INVALID,
        PENDING,
        DEAD
    }
}
