package com.example.meeting.events;

import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.model.MeetingEventOutboxStatus;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publishes committed meeting analysis events from the transactional outbox —
 * Faz 24 (platform-ai#244 BE-1d). A faithful port of the notification-orchestrator
 * {@code OutboxPoller}: an atomic {@code FOR UPDATE SKIP LOCKED} claim + lease +
 * per-cycle {@code claim_token}, crash-recoverable via stale-lease recovery.
 *
 * <h2>Commit-after-emit (Codex acceptance #1)</h2>
 * The poller only ever claims rows already committed by the ingestion transaction:
 * an uncommitted outbox row is invisible to the claim query's snapshot. Publishing
 * therefore happens strictly AFTER the run + children + outbox row durably
 * committed — never inside the ingestion transaction.
 *
 * <h2>Delivery lifecycle</h2>
 * <pre>
 *   recoverStaleLeases()  CLAIMED&amp;lease&lt;=now → PENDING   (crash recovery)
 *   claim()               PENDING → CLAIMED + lease         (atomic, SKIP LOCKED)
 *   publish() ok          CLAIMED → PUBLISHED               (terminal success)
 *   publish() fail        attempts++ &lt; max → PENDING (retry) | else → DEAD
 * </pre>
 *
 * <p>Bean is present only when {@code meeting.events.outbox.poller.enabled=true}
 * (default off — submit-only, like notify's dispatch gate), so it never
 * auto-ticks in unrelated contexts. Tests drive {@link #runCycle()} directly.
 */
@Component
@ConditionalOnProperty(name = "meeting.events.outbox.poller.enabled", havingValue = "true")
public class MeetingEventOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(MeetingEventOutboxPoller.class);

    private final MeetingEventOutboxRepository repository;
    private final MeetingEventPublisher publisher;
    private final int batchSize;
    private final long leaseDurationMs;
    private final int maxAttempts;
    private final boolean schedulingEnabled;
    private final String owner;

    private MeetingEventOutboxPoller self; // self-injection for a real @Transactional proxy boundary

    @Autowired
    void setSelf(@Lazy final MeetingEventOutboxPoller self) {
        this.self = self;
    }

    public MeetingEventOutboxPoller(
            final MeetingEventOutboxRepository repository,
            final MeetingEventPublisher publisher,
            @Value("${meeting.events.outbox.batch-size:100}") final int batchSize,
            @Value("${meeting.events.outbox.lease-duration-ms:60000}") final long leaseDurationMs,
            @Value("${meeting.events.outbox.max-attempts:8}") final int maxAttempts,
            @Value("${meeting.events.outbox.owner:}") final String owner,
            @Value("${meeting.events.outbox.scheduling-enabled:true}") final boolean schedulingEnabled) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.leaseDurationMs = leaseDurationMs;
        this.maxAttempts = maxAttempts;
        this.schedulingEnabled = schedulingEnabled;
        this.owner = (owner == null || owner.isBlank()) ? deriveOwner() : owner;
        log.info("MeetingEventOutboxPoller activated owner={} batchSize={} leaseMs={} maxAttempts={} scheduling={}",
                this.owner, batchSize, leaseDurationMs, maxAttempts, schedulingEnabled);
    }

    /** Scheduled tick (default fixedDelay 5s). Guarded so tests can drive runCycle() deterministically. */
    @Scheduled(fixedDelayString = "${meeting.events.outbox.poll-delay-ms:5000}")
    public void tick() {
        if (!schedulingEnabled) {
            return;
        }
        runCycle();
    }

    /** One poll cycle: recover stale leases, then claim + publish. Safe to call directly (tests). */
    public void runCycle() {
        try {
            int recovered = self.recoverStaleLeases();
            int published = claimAndPublish();
            if (recovered > 0 || published > 0) {
                log.info("MeetingEventOutboxPoller cycle recovered={} claimed={} owner={}",
                        recovered, published, owner);
            }
        } catch (RuntimeException e) {
            log.warn("MeetingEventOutboxPoller cycle error: {}", e.getMessage(), e);
        }
    }

    /** Crash recovery: stale-lease CLAIMED rows → PENDING. Self-invoked for a fresh tx. */
    @Transactional
    public int recoverStaleLeases() {
        return repository.recoverStaleLeases(Instant.now());
    }

    private int claimAndPublish() {
        Instant now = Instant.now();
        Instant leaseUntil = now.plusMillis(leaseDurationMs);
        UUID claimToken = UUID.randomUUID();
        int claimed = self.claimAtomic(now, leaseUntil, claimToken);
        if (claimed == 0) {
            return 0;
        }
        // Fetch ONLY this cycle's committed claims (multi-pod isolation by claim_token).
        List<MeetingEventOutbox> rows = repository.findByClaimToken(claimToken);
        for (MeetingEventOutbox row : rows) {
            publishOne(row);
        }
        return claimed;
    }

    /** Atomic native claim — own commit boundary. Public so a concurrency test can drive it. */
    @Transactional
    public int claimAtomic(final Instant now, final Instant leaseUntil, final UUID claimToken) {
        return repository.claimBatch(now, leaseUntil, owner, claimToken, batchSize);
    }

    private void publishOne(final MeetingEventOutbox row) {
        try {
            publisher.publish(MeetingEventMessage.from(row));
            self.markPublished(row.getId());
        } catch (RuntimeException e) {
            // Safe telemetry only — the exception class, never the payload.
            log.warn("meeting-event publish failed eventKey={} cause={}",
                    row.getEventKey(), e.getClass().getSimpleName());
            self.markFailed(row.getId(), e.getClass().getSimpleName());
        }
    }

    /** Terminal success: CLAIMED → PUBLISHED, lease cleared. */
    @Transactional
    public void markPublished(final UUID id) {
        MeetingEventOutbox row = repository.findById(id).orElse(null);
        if (row == null) {
            return;
        }
        row.setStatus(MeetingEventOutboxStatus.PUBLISHED);
        row.setPublishedAt(Instant.now());
        clearLease(row);
        repository.save(row);
    }

    /** Failure: attempts++, back to PENDING for retry, or DEAD once the budget is spent. */
    @Transactional
    public void markFailed(final UUID id, final String errorClass) {
        MeetingEventOutbox row = repository.findById(id).orElse(null);
        if (row == null) {
            return;
        }
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setLastError(errorClass);
        clearLease(row);
        row.setStatus(attempts >= maxAttempts
                ? MeetingEventOutboxStatus.DEAD
                : MeetingEventOutboxStatus.PENDING);
        repository.save(row);
    }

    private static void clearLease(final MeetingEventOutbox row) {
        row.setClaimToken(null);
        row.setProcessingOwner(null);
        row.setClaimedAt(null);
        row.setLeaseExpiresAt(null);
    }

    public String getOwner() {
        return owner;
    }

    private static String deriveOwner() {
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            String pid = String.valueOf(ProcessHandle.current().pid());
            return hostname + "-" + pid;
        } catch (Exception e) {
            return "meeting-outbox-" + System.nanoTime();
        }
    }
}
