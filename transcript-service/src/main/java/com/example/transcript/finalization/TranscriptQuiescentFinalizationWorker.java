package com.example.transcript.finalization;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;

/** Restart-safe scheduler; each association is finalized in its own transaction. */
@Component
@ConditionalOnProperty(name = "transcript.finalization.worker.enabled", havingValue = "true")
public class TranscriptQuiescentFinalizationWorker {

    private static final Logger log = LoggerFactory.getLogger(
            TranscriptQuiescentFinalizationWorker.class);

    private final TranscriptSessionAssociationRepository associations;
    private final TranscriptQuiescentFinalizationProcessor processor;
    private final TranscriptFinalizationProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public TranscriptQuiescentFinalizationWorker(
            TranscriptSessionAssociationRepository associations,
            TranscriptQuiescentFinalizationProcessor processor,
            TranscriptFinalizationProperties properties,
            MeterRegistry meters,
            Clock transcriptFinalizationClock) {
        this.associations = associations;
        this.processor = processor;
        this.properties = properties;
        this.meters = meters;
        this.clock = transcriptFinalizationClock;
    }

    @Scheduled(fixedDelayString = "${transcript.finalization.worker.poll-delay-ms:5000}")
    public void runOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<UUID> due = associations.findDueFinalizationIds(
                now, PageRequest.of(0, properties.getWorker().getBatchSize()));
        for (UUID associationId : due) {
            try {
                TranscriptQuiescentFinalizationProcessor.Outcome outcome =
                        processor.process(associationId);
                meters.counter("transcript_finalization_cycle_total",
                        "outcome", outcome.name().toLowerCase()).increment();
                if (outcome == TranscriptQuiescentFinalizationProcessor.Outcome.INVALID_SNAPSHOT) {
                    log.error("Canonical transcript snapshot invalid associationId={} reason={}",
                            associationId, TranscriptQuiescentFinalizationProcessor.INVALID_CANONICAL_SEGMENT);
                }
            } catch (RuntimeException ex) {
                meters.counter("transcript_finalization_cycle_total", "outcome", "error").increment();
                log.error("Canonical transcript finalization failed associationId={} error={}",
                        associationId, ex.getClass().getSimpleName());
            }
        }
    }
}
