package com.example.ethics.notification;

import com.example.ethics.model.NotificationOutbox;
import com.example.ethics.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Inserts a no-PII notification signal inside the caller's business transaction. */
@Component
public class NotificationOutboxPublisher {
    public static final String NEW_REPORT = "NEW_REPORT";
    public static final String REPORTER_MESSAGE = "REPORTER_MESSAGE";
    private static final Set<String> ALLOWED_EVENTS = Set.of(NEW_REPORT, REPORTER_MESSAGE);

    private final NotificationOutboxRepository outbox;

    public NotificationOutboxPublisher(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    public void enqueue(UUID orgId, String eventType, Instant now) {
        if (!ALLOWED_EVENTS.contains(eventType)) {
            throw new IllegalArgumentException("Unsupported ethics notification event");
        }
        outbox.save(new NotificationOutbox(UUID.randomUUID(), orgId, eventType, now));
    }
}
