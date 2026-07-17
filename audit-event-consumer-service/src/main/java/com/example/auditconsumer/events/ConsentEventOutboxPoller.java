package com.example.auditconsumer.events;

import com.example.auditconsumer.model.ConsentEventOutbox;
import com.example.auditconsumer.repository.ConsentEventOutboxRepository;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Lease- and token-fenced relay for committed consent-event outbox rows. */
@Component
@ConditionalOnProperty(
        name = {
                "audit.consent-events.outbox.poller.enabled",
                "audit.consent-events.redis.enabled"
        },
        havingValue = "true")
public class ConsentEventOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(ConsentEventOutboxPoller.class);

    private final ConsentEventOutboxRepository repository;
    private final ConsentEventPublisher publisher;
    private final int batchSize;
    private final long leaseDurationMs;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final String owner;
    private final boolean schedulingEnabled;
    private ConsentEventOutboxPoller self;

    public ConsentEventOutboxPoller(
            ConsentEventOutboxRepository repository,
            ConsentEventPublisher publisher,
            @Value("${audit.consent-events.outbox.batch-size:100}") int batchSize,
            @Value("${audit.consent-events.outbox.lease-duration-ms:60000}") long leaseDurationMs,
            @Value("${audit.consent-events.outbox.max-attempts:8}") int maxAttempts,
            @Value("${audit.consent-events.outbox.retry-delay-ms:5000}") long retryDelayMs,
            @Value("${audit.consent-events.outbox.owner:}") String owner,
            @Value("${audit.consent-events.outbox.scheduling-enabled:true}") boolean schedulingEnabled) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.leaseDurationMs = leaseDurationMs;
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;
        this.owner = owner == null || owner.isBlank() ? deriveOwner() : owner;
        this.schedulingEnabled = schedulingEnabled;
    }

    @Autowired
    void setSelf(@Lazy ConsentEventOutboxPoller self) {
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${audit.consent-events.outbox.poll-delay-ms:5000}")
    public void tick() {
        if (schedulingEnabled) {
            runCycle();
        }
    }

    public void runCycle() {
        try {
            self.recoverStaleLeases();
            reportDeadRows();
            claimAndPublish();
        } catch (RuntimeException ex) {
            log.warn("consent-event outbox cycle failed cause={}", ex.getClass().getSimpleName());
        }
    }

    @Transactional
    public int recoverStaleLeases() {
        Instant now = Instant.now();
        return repository.recoverStaleLeases(now, now.plusMillis(retryDelayMs), maxAttempts);
    }

    private int claimAndPublish() {
        Instant now = Instant.now();
        UUID claimToken = UUID.randomUUID();
        int claimed = self.claimAtomic(now, now.plusMillis(leaseDurationMs), claimToken);
        if (claimed == 0) {
            return 0;
        }
        List<ConsentEventOutbox> rows = repository.findByClaimToken(claimToken);
        for (ConsentEventOutbox row : rows) {
            try {
                publisher.publish(ConsentEventMessage.from(row));
                self.markPublished(row.getId(), row.getClaimToken());
            } catch (RuntimeException ex) {
                self.markFailed(row.getId(), row.getClaimToken(), ex.getClass().getSimpleName());
            }
        }
        return claimed;
    }

    @Transactional
    public int claimAtomic(Instant now, Instant leaseUntil, UUID claimToken) {
        return repository.claimBatch(now, leaseUntil, owner, claimToken, batchSize);
    }

    @Transactional
    public void markPublished(UUID id, UUID claimToken) {
        if (claimToken == null || repository.markPublishedFenced(id, claimToken, Instant.now()) == 0) {
            log.warn("consent-event publish outcome discarded id={} leaseLost=true", id);
        }
    }

    @Transactional
    public void markFailed(UUID id, UUID claimToken, String errorClass) {
        Instant now = Instant.now();
        if (claimToken == null
                || repository.markFailedFenced(
                        id, claimToken, errorClass, maxAttempts,
                        now.plusMillis(retryDelayMs), now) == 0) {
            log.warn("consent-event failure outcome discarded id={} leaseLost=true", id);
        }
    }

    private void reportDeadRows() {
        long deadRows = repository.countByStatus(
                com.example.auditconsumer.model.ConsentEventOutboxStatus.DEAD);
        if (deadRows > 0) {
            log.error("consent-event outbox requires operator redrive deadRows={} "
                    + "function=consent_event_outbox_redrive", deadRows);
        }
    }

    private static String deriveOwner() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception ex) {
            return "consent-outbox-" + System.nanoTime();
        }
    }
}
