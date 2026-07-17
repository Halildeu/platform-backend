package com.example.transcript.events;

import com.example.transcript.model.TranscriptEventOutbox;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
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

/** Lease-fenced, multi-pod-safe poller for transcript-service's own outbox. */
@Component
@ConditionalOnProperty(name = "transcript.events.outbox.poller.enabled", havingValue = "true")
public class TranscriptEventOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(TranscriptEventOutboxPoller.class);

    private final TranscriptEventOutboxRepository repository;
    private final TranscriptMeetingEventPublisher publisher;
    private final int batchSize;
    private final long leaseDurationMs;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final boolean schedulingEnabled;
    private final String owner;
    private TranscriptEventOutboxPoller self;

    @Autowired
    void setSelf(@Lazy TranscriptEventOutboxPoller self) {
        this.self = self;
    }

    public TranscriptEventOutboxPoller(
            TranscriptEventOutboxRepository repository,
            TranscriptMeetingEventPublisher publisher,
            @Value("${transcript.events.outbox.batch-size:100}") int batchSize,
            @Value("${transcript.events.outbox.lease-duration-ms:60000}") long leaseDurationMs,
            @Value("${transcript.events.outbox.max-attempts:8}") int maxAttempts,
            @Value("${transcript.events.outbox.retry-delay-ms:5000}") long retryDelayMs,
            @Value("${transcript.events.outbox.owner:}") String configuredOwner,
            @Value("${transcript.events.outbox.scheduling-enabled:true}") boolean schedulingEnabled) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = Math.max(1, batchSize);
        this.leaseDurationMs = Math.max(1, leaseDurationMs);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
        this.schedulingEnabled = schedulingEnabled;
        this.owner = configuredOwner == null || configuredOwner.isBlank()
                ? deriveOwner() : configuredOwner;
    }

    @Scheduled(fixedDelayString = "${transcript.events.outbox.poll-delay-ms:5000}")
    public void tick() {
        if (schedulingEnabled) {
            runCycle();
        }
    }

    public void runCycle() {
        try {
            self.recoverStaleLeases();
            UUID token = UUID.randomUUID();
            Instant now = Instant.now();
            int claimed = self.claimAtomic(
                    now, now.plusMillis(leaseDurationMs), token);
            if (claimed == 0) {
                return;
            }
            List<TranscriptEventOutbox> rows = repository.findByClaimToken(token);
            rows.forEach(this::publishOne);
        } catch (RuntimeException ex) {
            log.warn("Transcript event outbox cycle failed cause={}", ex.getClass().getSimpleName());
        }
    }

    @Transactional
    public int recoverStaleLeases() {
        Instant now = Instant.now();
        return repository.recoverStaleLeases(
                now, now.plusMillis(retryDelayMs), maxAttempts);
    }

    @Transactional
    public int claimAtomic(Instant now, Instant leaseUntil, UUID token) {
        return repository.claimBatch(now, leaseUntil, owner, token, batchSize);
    }

    private void publishOne(TranscriptEventOutbox row) {
        try {
            publisher.publish(TranscriptMeetingEventMessage.from(row));
            self.markPublished(row.getId(), row.getClaimToken());
        } catch (RuntimeException ex) {
            log.warn("Transcript meeting-event publish failed eventKey={} cause={}",
                    row.getEventKey(), ex.getClass().getSimpleName());
            self.markFailed(row.getId(), row.getClaimToken(), ex.getClass().getSimpleName());
        }
    }

    @Transactional
    public void markPublished(UUID id, UUID token) {
        if (token != null && repository.markPublishedFenced(id, token, Instant.now()) == 0) {
            log.warn("Transcript event publish outcome discarded after lease loss id={}", id);
        }
    }

    @Transactional
    public void markFailed(UUID id, UUID token, String errorClass) {
        Instant now = Instant.now();
        if (token != null && repository.markFailedFenced(
                id, token, errorClass, maxAttempts,
                now.plusMillis(retryDelayMs), now) == 0) {
            log.warn("Transcript event failure outcome discarded after lease loss id={}", id);
        }
    }

    private static String deriveOwner() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName()
                    + "-" + ProcessHandle.current().pid();
        } catch (Exception ex) {
            return "transcript-outbox-" + System.nanoTime();
        }
    }
}
