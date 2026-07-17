package com.example.transcript.directstt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectSttTranscriptResultHandlerTest {

    private final TranscriptSessionAssociationService associationService =
            mock(TranscriptSessionAssociationService.class);
    private final DirectSttTranscriptIngestionService ingestionService =
            mock(DirectSttTranscriptIngestionService.class);
    private final DirectSttTranscriptResultHandler handler =
            new DirectSttTranscriptResultHandler(associationService, ingestionService);

    @Test
    void handleValidEventRoutesToSegmentService() {
        UUID canonicalSessionId = UUID.randomUUID();
        when(associationService.resolve(any(DirectSttTranscriptResultEvent.class)))
                .thenReturn(TranscriptSessionAssociationService.Outcome.resolved(canonicalSessionId));

        DirectSttTranscriptResultHandler.HandleOutcome outcome =
                handler.handle(DirectSttTranscriptResultEventTest.validFields(), "1-0");

        assertThat(outcome.result()).isEqualTo(DirectSttTranscriptResultHandler.Result.PROCESSED);
        verify(ingestionService).upsert(any(DirectSttTranscriptResultEvent.class),
                org.mockito.ArgumentMatchers.eq(canonicalSessionId));
    }

    @Test
    void handleMissingAssociationRemainsPendingAndDoesNotWriteSegment() {
        when(associationService.resolve(any(DirectSttTranscriptResultEvent.class)))
                .thenReturn(TranscriptSessionAssociationService.Outcome.pending("MAPPING_NOT_FOUND"));

        DirectSttTranscriptResultHandler.HandleOutcome outcome =
                handler.handle(DirectSttTranscriptResultEventTest.validFields(), "1-0");

        assertThat(outcome.result()).isEqualTo(DirectSttTranscriptResultHandler.Result.PENDING);
        assertThat(outcome.reason()).isEqualTo("MAPPING_NOT_FOUND");
        verify(ingestionService, never()).upsert(any(), any());
    }

    @Test
    void handleInvalidEventDoesNotCallServiceAndKeepsReasonTranscriptFree() {
        Map<String, String> fields = DirectSttTranscriptResultEventTest.validFields();
        fields.remove("chunkSeq");
        fields.put("textDraft", "secret transcript phrase");

        DirectSttTranscriptResultHandler.HandleOutcome outcome = handler.handle(fields, "1-0");

        assertThat(outcome.result()).isEqualTo(DirectSttTranscriptResultHandler.Result.INVALID);
        assertThat(outcome.reason()).contains("chunkSeq").doesNotContain("secret transcript phrase");
        verify(associationService, never()).resolve(any(DirectSttTranscriptResultEvent.class));
        verify(ingestionService, never()).upsert(any(), any());
    }
}
