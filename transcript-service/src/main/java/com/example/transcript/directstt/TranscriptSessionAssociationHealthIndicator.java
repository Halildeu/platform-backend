package com.example.transcript.directstt;

import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Content-free mapping backlog/dead-letter visibility. */
@Component("transcriptSessionAssociation")
public class TranscriptSessionAssociationHealthIndicator implements HealthIndicator {

    private final TranscriptSessionAssociationRepository repository;

    public TranscriptSessionAssociationHealthIndicator(
            TranscriptSessionAssociationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        long pending = repository.countByStatus(TranscriptSessionAssociationStatus.PENDING);
        long resolving = repository.countByStatus(TranscriptSessionAssociationStatus.RESOLVING);
        long dead = repository.countByStatus(TranscriptSessionAssociationStatus.DEAD);
        Health.Builder result = dead > 0 ? Health.down() : Health.up();
        return result.withDetail("pending", pending)
                .withDetail("resolving", resolving)
                .withDetail("dead", dead)
                .build();
    }
}
