package com.example.ethics.notification;

import com.example.ethics.audit.EthicsAuditChain;
import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.model.NotificationOutbox;
import com.example.ethics.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Leased, replay-safe provider delivery for no-PII notification signals.
 *
 * <p>A provider-accepted request is replayed with the same intent/idempotency
 * key if the process dies before the local checkpoint. Logs include only the
 * delivery state and error class, never org, recipient, token or business data.
 */
@Component
public class NotificationOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxWorker.class);
    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "PROCESSING");

    private final NotificationOutboxRepository outbox;
    private final NotificationIntentGateway gateway;
    private final NotificationCheckpointService checkpoint;
    private final NotificationRetryService retry;
    private final NotificationDeliveryProperties properties;
    private final TransactionTemplate transactions;
    private final Counter deliveredCounter;
    private final Counter retryCounter;
    private final Counter deadLetterCounter;

    public NotificationOutboxWorker(
            NotificationOutboxRepository outbox,
            NotificationIntentGateway gateway,
            NotificationCheckpointService checkpoint,
            NotificationRetryService retry,
            NotificationDeliveryProperties properties,
            TransactionTemplate transactions,
            MeterRegistry metrics) {
        this.outbox = outbox;
        this.gateway = gateway;
        this.checkpoint = checkpoint;
        this.retry = retry;
        this.properties = properties;
        this.transactions = transactions;
        this.deliveredCounter = Counter.builder("ethics.notification.outbox.delivered")
                .description("Etik Speak notification intents accepted by the provider")
                .register(metrics);
        this.retryCounter = Counter.builder("ethics.notification.outbox.retry")
                .description("Etik Speak notification intents scheduled for retry")
                .register(metrics);
        this.deadLetterCounter = Counter.builder("ethics.notification.outbox.dead.letter")
                .description("Etik Speak notification intents exhausted into DLQ")
                .register(metrics);
        Gauge.builder("ethics.notification.outbox.pending.entries", outbox,
                        repository -> repository.countByStatusIn(BACKLOG_STATUSES))
                .description("Pending or leased Etik Speak notification intents")
                .register(metrics);
        Gauge.builder("ethics.notification.outbox.dead.letter.entries", outbox,
                        repository -> repository.countByStatus("DEAD_LETTER"))
                .description("Etik Speak notification intents requiring operator action")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${ethics.notification-delivery.poll-delay:5s}")
    void scheduledCycle() {
        if (properties.isEnabled()) {
            runCycle();
        }
    }

    /** Public for deterministic TEST outage and recovery drills. */
    public CycleResult runCycle() {
        Instant now = EthicsAuditChain.normalizeTimestamp(Instant.now());
        Instant lockedUntil = EthicsAuditChain.normalizeTimestamp(
                now.plus(properties.getLeaseDuration()));
        UUID claimToken = UUID.randomUUID();

        int recovered = transactions.execute(status -> outbox.recoverExpiredLeases(now));
        int claimed = transactions.execute(status -> outbox.claimDue(
                claimToken, now, lockedUntil, properties.getBatchSize()));
        if (claimed == 0) {
            return new CycleResult(recovered, 0, 0, 0, 0);
        }

        List<NotificationOutbox> rows =
                outbox.findByClaimTokenOrderByCreatedAtAsc(claimToken);
        if (rows.size() != claimed) {
            throw new IllegalStateException("Notification claim cardinality mismatch");
        }

        int delivered = 0;
        int retried = 0;
        int deadLettered = 0;
        for (NotificationOutbox row : rows) {
            try {
                gateway.submit(row);
                checkpoint.markDelivered(
                        row.getId(),
                        claimToken,
                        lockedUntil,
                        EthicsAuditChain.normalizeTimestamp(Instant.now()));
                delivered++;
                deliveredCounter.increment();
            } catch (RuntimeException error) {
                NotificationRetryService.RetryResult result = retry.recordFailure(
                        row.getId(),
                        claimToken,
                        lockedUntil,
                        row.getAttemptCount(),
                        EthicsAuditChain.normalizeTimestamp(Instant.now()),
                        error);
                if (result == NotificationRetryService.RetryResult.DEAD_LETTER) {
                    deadLettered++;
                    deadLetterCounter.increment();
                    log.error("Etik Speak notification entered DEAD_LETTER attempt={}",
                            row.getAttemptCount());
                } else {
                    retried++;
                    retryCounter.increment();
                    log.warn("Etik Speak notification scheduled retry attempt={} error={}",
                            row.getAttemptCount(), error.getClass().getSimpleName());
                }
            }
        }
        log.info(
                "Etik Speak notification cycle owner={} recovered={} claimed={} delivered={} retry={} dlq={}",
                properties.getOwner(),
                recovered,
                claimed,
                delivered,
                retried,
                deadLettered);
        return new CycleResult(recovered, claimed, delivered, retried, deadLettered);
    }

    public record CycleResult(
            int recovered,
            int claimed,
            int delivered,
            int retryScheduled,
            int deadLettered) {
    }
}
