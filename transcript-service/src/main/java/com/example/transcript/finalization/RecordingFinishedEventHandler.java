package com.example.transcript.finalization;

import com.example.transcript.finalization.RecordingFinishedEventParser.RecordingFinishedEventInvalidException;
import com.example.transcript.finalization.RecordingFinishedEventProcessor.RecordingFinishedEventConflictException;
import com.example.transcript.service.SessionErasureFence.SessionErasedException;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Non-transactional outcome adapter; processor exceptions have already rolled back. */
@Component
public class RecordingFinishedEventHandler {

    private final RecordingFinishedEventParser parser;
    private final RecordingFinishedEventProcessor processor;

    public RecordingFinishedEventHandler(
            RecordingFinishedEventParser parser,
            RecordingFinishedEventProcessor processor) {
        this.parser = parser;
        this.processor = processor;
    }

    public HandleOutcome handle(Map<String, String> fields) {
        try {
            RecordingFinishedEvent event = parser.parse(fields);
            if (event == null) {
                return new HandleOutcome(HandleResult.IGNORED, "OTHER_EVENT_TYPE");
            }
            return switch (processor.process(event)) {
                case PROCESSED -> new HandleOutcome(HandleResult.PROCESSED, null);
                case DUPLICATE -> new HandleOutcome(HandleResult.DUPLICATE, null);
            };
        } catch (RecordingFinishedEventInvalidException ex) {
            return new HandleOutcome(HandleResult.INVALID, ex.reason());
        } catch (RecordingFinishedEventConflictException
                | TranscriptFinalizationStateMachine.FinalizationScopeConflictException
                | SessionErasedException ex) {
            return new HandleOutcome(HandleResult.DEAD, ex.getMessage());
        }
    }

    public enum HandleResult { PROCESSED, DUPLICATE, IGNORED, INVALID, DEAD }
    public record HandleOutcome(HandleResult result, String reason) { }
}
