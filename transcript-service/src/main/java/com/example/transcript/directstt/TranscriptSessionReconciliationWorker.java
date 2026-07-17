package com.example.transcript.directstt;

import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, idempotent reconciliation for V4-seeded historical segments. */
@Component
@ConditionalOnProperty(
        name = "transcript.direct-stt-result-consumer.mapping.enabled",
        havingValue = "true")
public class TranscriptSessionReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(TranscriptSessionReconciliationWorker.class);

    private final TranscriptSessionAssociationRepository repository;
    private final TranscriptSessionAssociationService service;
    private final DirectSttTranscriptResultConsumerProperties properties;

    public TranscriptSessionReconciliationWorker(
            TranscriptSessionAssociationRepository repository,
            TranscriptSessionAssociationService service,
            DirectSttTranscriptResultConsumerProperties properties) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString =
            "${transcript.direct-stt-result-consumer.mapping.reconciliation-poll-millis:30000}")
    public void reconcileDue() {
        int limit = Math.max(1, properties.getMapping().getReconciliationBatchSize());
        var due = repository.findDue(Instant.now(), PageRequest.of(0, limit));
        int resolved = 0;
        int dead = 0;
        for (var association : due) {
            var outcome = service.resolve(
                    association.getTenantId(), association.getMeetingId(), association.getSourceSessionId());
            if (outcome.result() == TranscriptSessionAssociationService.Result.RESOLVED) {
                resolved++;
            } else if (outcome.result() == TranscriptSessionAssociationService.Result.DEAD) {
                dead++;
            }
        }
        if (!due.isEmpty()) {
            log.info("Transcript session reconciliation attempted={} resolved={} dead={}",
                    due.size(), resolved, dead);
        }
    }
}
