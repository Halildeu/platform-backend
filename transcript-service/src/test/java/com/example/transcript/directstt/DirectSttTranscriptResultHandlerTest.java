package com.example.transcript.directstt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.transcript.service.TranscriptSegmentService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DirectSttTranscriptResultHandlerTest {

    private final TranscriptSegmentService segmentService = mock(TranscriptSegmentService.class);
    private final DirectSttTranscriptResultHandler handler =
            new DirectSttTranscriptResultHandler(segmentService);

    @Test
    void handleValidEventRoutesToSegmentService() {
        DirectSttTranscriptResultHandler.HandleOutcome outcome =
                handler.handle(DirectSttTranscriptResultEventTest.validFields(), "1-0");

        assertThat(outcome.result()).isEqualTo(DirectSttTranscriptResultHandler.Result.PROCESSED);
        verify(segmentService).upsertDirectSttDraft(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void handleInvalidEventDoesNotCallServiceAndKeepsReasonTranscriptFree() {
        Map<String, String> fields = DirectSttTranscriptResultEventTest.validFields();
        fields.remove("chunkSeq");
        fields.put("textDraft", "secret transcript phrase");

        DirectSttTranscriptResultHandler.HandleOutcome outcome = handler.handle(fields, "1-0");

        assertThat(outcome.result()).isEqualTo(DirectSttTranscriptResultHandler.Result.INVALID);
        assertThat(outcome.reason()).contains("chunkSeq").doesNotContain("secret transcript phrase");
        verify(segmentService, never()).upsertDirectSttDraft(org.mockito.ArgumentMatchers.any());
    }
}
