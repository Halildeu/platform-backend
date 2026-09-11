package com.example.ethics.notification;

import com.example.ethics.repository.NotificationOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "One signal per organisation, per event, per rolling day" — held across replicas.
 *
 * <p>ES-301b (#1153). The SLA sweeper's read-then-insert lets two transactions on two cases
 * of the same organisation both find an empty day and both enqueue; the orchestrator cannot
 * fold them because each outbox row is its own idempotency key. This claims the budget with
 * a single {@code UPDATE … WHERE last_signal_at <= now - window} on the
 * {@code ethics_notification_signal_window} row (V27): the second transaction waits on the
 * row lock, then sees the fresh timestamp and gets 0 rows. Runs inside the caller's business
 * transaction on purpose — a rolled-back escalation gives the budget back with it.
 *
 * <p>The row is opened lazily in its own transaction so that the one-time race of two
 * replicas creating it at once (a primary-key violation) never poisons the caller's.
 */
@Component
public class NotificationSignalBudget {
    /** Rolling window, not a calendar day: at most one new signal in any 24 hours. */
    public static final Duration WINDOW = Duration.ofDays(1);

    private final NotificationOutboxRepository outbox;
    private final TransactionOperations ownTransaction;

    @Autowired
    public NotificationSignalBudget(NotificationOutboxRepository outbox, PlatformTransactionManager transactions) {
        this(outbox, requiresNew(transactions));
    }

    NotificationSignalBudget(NotificationOutboxRepository outbox, TransactionOperations ownTransaction) {
        this.outbox = outbox;
        this.ownTransaction = ownTransaction;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** True when the caller owns this window's signal for {@code now}; false → suppressed. */
    public boolean claim(UUID orgId, String eventType, Instant now) {
        open(orgId, eventType);
        return outbox.claimSignalWindow(orgId, eventType, now, now.minus(WINDOW)) == 1;
    }

    private void open(UUID orgId, String eventType) {
        if (outbox.countSignalWindow(orgId, eventType) > 0) return;
        try {
            ownTransaction.executeWithoutResult(status -> outbox.openSignalWindow(orgId, eventType, Instant.EPOCH));
        } catch (DataIntegrityViolationException raced) {
            // Another replica opened the same window first; the claim below decides who signals.
        }
    }
}
