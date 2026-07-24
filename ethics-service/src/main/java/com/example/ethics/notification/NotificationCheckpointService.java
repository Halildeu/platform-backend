package com.example.ethics.notification;

import com.example.ethics.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** CAS-checkpoints a provider-accepted intent in a fresh transaction. */
@Service
public class NotificationCheckpointService {
    private final NotificationOutboxRepository outbox;

    public NotificationCheckpointService(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDelivered(
            UUID id,
            UUID claimToken,
            Instant lockedUntil,
            Instant deliveredAt) {
        int updated = outbox.markDelivered(id, claimToken, lockedUntil, deliveredAt);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Notification delivery checkpoint CAS fence rejected stale worker");
        }
    }
}
