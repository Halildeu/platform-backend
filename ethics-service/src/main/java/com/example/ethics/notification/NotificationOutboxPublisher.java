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

    /**
     * An obligation in this organisation has passed its legal deadline.
     *
     * <p>The first signal that is not "something arrived". Both existing events fire when a
     * reporter acts; this one fires when the organisation has <em>not</em> acted, which is
     * the case nobody was being told about — 51 breached acknowledgements on the live cell
     * and no message anywhere.
     *
     * <p>Carries no case id, exactly like its two siblings. Which cases are overdue is a
     * question for the staff list, which now orders by how far past the deadline each one
     * is; sending that detail through a notification channel would put case-level facts
     * into a transport the outbox was deliberately kept free of.
     */
    public static final String SLA_BREACH = "SLA_BREACH";
    private static final Set<String> ALLOWED_EVENTS = Set.of(NEW_REPORT, REPORTER_MESSAGE, SLA_BREACH);

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
