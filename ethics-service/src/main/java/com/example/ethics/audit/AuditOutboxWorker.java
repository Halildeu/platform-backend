package com.example.ethics.audit;

import com.example.ethics.config.AuditDeliveryProperties;
import com.example.ethics.model.AuditOutbox;
import com.example.ethics.repository.AuditOutboxRepository;
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
 * Bounded lease/claim/deliver loop for the Etik Speak audit outbox.
 *
 * <p>Only identifiers and error classes are logged. Event payloads, subjects,
 * narrative and reporter credentials never enter operational logs.
 */
@Component
public class AuditOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(AuditOutboxWorker.class);
    private static final List<String> BACKLOG_STATUSES = List.of("PENDING", "PROCESSING");

    private final AuditOutboxRepository outbox;
    private final AuditDeliveryService delivery;
    private final AuditRetryService retry;
    private final AuditDeliveryProperties properties;
    private final TransactionTemplate transactions;
    private final Counter deliveredCounter;
    private final Counter retryCounter;
    private final Counter deadLetterCounter;

    public AuditOutboxWorker(
            AuditOutboxRepository outbox,
            AuditDeliveryService delivery,
            AuditRetryService retry,
            AuditDeliveryProperties properties,
            TransactionTemplate transactions,
            MeterRegistry metrics) {
        this.outbox = outbox;
        this.delivery = delivery;
        this.retry = retry;
        this.properties = properties;
        this.transactions = transactions;
        this.deliveredCounter = Counter.builder("ethics.audit.outbox.delivered")
                .description("Etik Speak audit outbox rows checkpointed into the WORM ledger")
                .register(metrics);
        this.retryCounter = Counter.builder("ethics.audit.outbox.retry")
                .description("Etik Speak audit delivery failures scheduled for retry")
                .register(metrics);
        this.deadLetterCounter = Counter.builder("ethics.audit.outbox.dead.letter")
                .description("Etik Speak audit rows exhausted into the durable DLQ state")
                .register(metrics);
        Gauge.builder("ethics.audit.outbox.pending.entries", outbox,
                        repository -> repository.countByStatusIn(BACKLOG_STATUSES))
                .description("Pending or leased Etik Speak audit outbox entries")
                .register(metrics);
        Gauge.builder("ethics.audit.outbox.dead.letter.entries", outbox,
                        repository -> repository.countByStatus("DEAD_LETTER"))
                .description("Etik Speak audit entries requiring operator action")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${ethics.audit-delivery.poll-delay:5s}")
    void scheduledCycle() {
        if (properties.enabled()) {
            runCycle();
        }
    }

    /** Public for deterministic TEST acceptance; scheduled activation remains overlay-controlled. */
    public CycleResult runCycle() {
        Instant now = EthicsAuditChain.normalizeTimestamp(Instant.now());
        Instant lockedUntil = EthicsAuditChain.normalizeTimestamp(now.plus(properties.leaseDuration()));
        UUID claimToken = UUID.randomUUID();

        int recovered = transactions.execute(status -> outbox.recoverExpiredLeases(now));
        int claimed = transactions.execute(status ->
                outbox.claimDue(claimToken, now, lockedUntil, properties.batchSize()));
        if (claimed == 0) {
            return new CycleResult(recovered, 0, 0, 0, 0);
        }

        List<AuditOutbox> rows = outbox.findByClaimTokenOrderByCreatedAtAsc(claimToken);
        if (rows.size() != claimed) {
            throw new IllegalStateException("Audit claim cardinality mismatch");
        }

        int delivered = 0;
        int retried = 0;
        int deadLettered = 0;
        for (AuditOutbox row : rows) {
            try {
                delivery.deliver(row.getId(), claimToken, lockedUntil,
                        EthicsAuditChain.normalizeTimestamp(Instant.now()));
                delivered++;
                deliveredCounter.increment();
            } catch (RuntimeException error) {
                AuditRetryService.RetryResult result = retry.recordFailure(
                        row.getId(),
                        claimToken,
                        lockedUntil,
                        row.getAttemptCount(),
                        EthicsAuditChain.normalizeTimestamp(Instant.now()),
                        error);
                if (result == AuditRetryService.RetryResult.DEAD_LETTER) {
                    deadLettered++;
                    deadLetterCounter.increment();
                    log.error("Etik Speak audit delivery entered DEAD_LETTER attempt={}",
                            row.getAttemptCount());
                } else {
                    retried++;
                    retryCounter.increment();
                    log.warn("Etik Speak audit delivery scheduled retry attempt={} error={}",
                            row.getAttemptCount(), error.getClass().getSimpleName());
                }
            }
        }
        log.info("Etik Speak audit delivery cycle owner={} recovered={} claimed={} delivered={} retry={} dlq={}",
                properties.owner(), recovered, claimed, delivered, retried, deadLettered);
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
