package com.example.ethics.notification;

import com.example.ethics.config.EthicsSlaEscalationProperties;
import com.example.ethics.model.NotificationOutbox;
import com.example.ethics.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.OptionalInt;
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
    /** ES-301 (#882): a legal deadline is close in working days — urgency, not yet failure. */
    public static final String SLA_APPROACHING = "SLA_APPROACHING";

    /**
     * ES-301b (#1153): an obligation crossed an escalation threshold. One event per level,
     * {@code CASE_ESCALATED_L1} … {@code CASE_ESCALATED_L5}: the outbox row has no payload,
     * so the level rides in the event type, and the daily budget keyed on (org, event) then
     * counts each level on its own. Level 1 goes to the first tier, higher levels to the
     * second tier — the routing lives in {@link NotificationIntentPayloadFactory}.
     */
    static final String CASE_ESCALATED_PREFIX = "CASE_ESCALATED_L";

    private static final Set<String> ALLOWED_EVENTS = allowedEvents();

    private static Set<String> allowedEvents() {
        Set<String> events = new LinkedHashSet<>(
                Set.of(NEW_REPORT, REPORTER_MESSAGE, SLA_BREACH, SLA_APPROACHING));
        for (int level = 1; level <= EthicsSlaEscalationProperties.MAX_LEVELS; level++) {
            events.add(CASE_ESCALATED_PREFIX + level);
        }
        return Set.copyOf(events);
    }

    /** Every event the Java side will write; the CHECK in V27 must list exactly these. */
    public static Set<String> allowed() {
        return ALLOWED_EVENTS;
    }

    /** The event for an escalation level; refuses levels the policy can never reach. */
    public static String escalation(int level) {
        if (level < 1 || level > EthicsSlaEscalationProperties.MAX_LEVELS) {
            throw new IllegalArgumentException("Unsupported ethics escalation level");
        }
        return CASE_ESCALATED_PREFIX + level;
    }

    /** The level an escalation event carries, or empty for any other (or malformed) event. */
    public static OptionalInt escalationLevel(String eventType) {
        if (eventType == null || !eventType.startsWith(CASE_ESCALATED_PREFIX)) return OptionalInt.empty();
        String suffix = eventType.substring(CASE_ESCALATED_PREFIX.length());
        if (suffix.length() != 1 || suffix.charAt(0) < '1' || suffix.charAt(0) > '9') return OptionalInt.empty();
        int level = suffix.charAt(0) - '0';
        return level <= EthicsSlaEscalationProperties.MAX_LEVELS ? OptionalInt.of(level) : OptionalInt.empty();
    }

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
