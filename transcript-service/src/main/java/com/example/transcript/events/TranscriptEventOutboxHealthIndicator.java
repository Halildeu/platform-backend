package com.example.transcript.events;

import com.example.transcript.model.TranscriptEventOutboxStatus;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Metadata-only visibility into queued and terminal transcript event delivery. */
@Component
public class TranscriptEventOutboxHealthIndicator implements HealthIndicator {

    private final TranscriptEventOutboxRepository repository;

    public TranscriptEventOutboxHealthIndicator(TranscriptEventOutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        long pending = repository.countByStatus(TranscriptEventOutboxStatus.PENDING);
        long claimed = repository.countByStatus(TranscriptEventOutboxStatus.CLAIMED);
        long dead = repository.countByStatus(TranscriptEventOutboxStatus.DEAD);
        Health.Builder result = dead > 0 ? Health.down() : Health.up();
        return result.withDetail("pending", pending)
                .withDetail("claimed", claimed)
                .withDetail("dead", dead)
                .build();
    }
}
