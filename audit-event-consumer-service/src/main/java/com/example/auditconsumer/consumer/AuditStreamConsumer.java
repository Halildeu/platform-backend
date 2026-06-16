package com.example.auditconsumer.consumer;

import com.example.auditconsumer.config.AuditConsumerProperties;
import com.example.auditconsumer.service.AuditEventPersistenceService;
import com.example.auditconsumer.service.AuditEventPersistenceService.PersistResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — Redis Streams audit:events consumer.
 *
 * <p>Single dedicated thread runs an at-least-once consume loop:
 * <ol>
 *   <li>idempotent group creation ({@code XGROUP CREATE ... MKSTREAM});</li>
 *   <li>on each cycle, first reclaim this group's stale pending entries
 *       (crashed-instance recovery via {@code XCLAIM} of entries idle beyond
 *       {@code claim-min-idle}), then {@code XREADGROUP ... BLOCK} for new ones;</li>
 *   <li>persist each record (immutable + hash-chained) then {@code XACK} —
 *       ACK strictly AFTER a successful persist, so a crash before persist
 *       leaves the entry in the PEL for redelivery (no audit loss);</li>
 *   <li>duplicate / malformed records are ACKed-and-skipped (idempotency +
 *       poison-message safety) so they do not wedge the PEL.</li>
 * </ol>
 *
 * <p>Active only when {@code audit.consumer.enabled=true} (default). Persistence
 * + the {@code audit_event} schema exist regardless of this flag, so a unit
 * test can wire the persistence path without a Redis dependency.
 *
 * <p>Graceful shutdown: {@link #stop()} flips the run flag and interrupts the
 * blocked {@code XREADGROUP}; the loop drains and exits.
 */
@Component
@ConditionalOnProperty(name = "audit.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class AuditStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditStreamConsumer.class);

    private final StringRedisTemplate redis;
    private final AuditEventPersistenceService persistence;
    private final AuditConsumerProperties props;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean groupReady = new AtomicBoolean(false);
    private final AtomicLong lastLoopAtMs = new AtomicLong(0L);
    private volatile Thread loopThread;

    public AuditStreamConsumer(StringRedisTemplate redis,
                               AuditEventPersistenceService persistence,
                               AuditConsumerProperties props) {
        this.redis = redis;
        this.persistence = persistence;
        this.props = props;
    }

    /** Start the loop once the context is ready (not at bean-init, so DB/Redis are up). */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running.compareAndSet(false, true)) {
            loopThread = new Thread(this::runLoop, "audit-stream-consumer");
            loopThread.setDaemon(true);
            loopThread.start();
            log.info("Audit stream consumer started stream={} group={} consumer={}",
                    props.getStream().getKey(), props.getGroup().getName(), props.getGroup().getConsumer());
        }
    }

    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping audit stream consumer");
            Thread t = loopThread;
            if (t != null) {
                t.interrupt();
                try {
                    t.join(Duration.ofSeconds(10).toMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** True once the consumer group has been created/confirmed. */
    public boolean isGroupReady() {
        return groupReady.get();
    }

    /** True while the loop thread is alive and the run flag is set. */
    public boolean isRunning() {
        Thread t = loopThread;
        return running.get() && t != null && t.isAlive();
    }

    /** Epoch-ms of the last loop iteration start (liveness signal for health). */
    public long lastLoopAtMs() {
        return lastLoopAtMs.get();
    }

    // ----- loop ------------------------------------------------------------

    private void runLoop() {
        while (running.get()) {
            // Shutdown via stop() flips the run flag AND interrupts the thread;
            // the blocked XREADGROUP unblocks as a Redis exception, but the
            // interrupt flag is the authoritative exit signal.
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            lastLoopAtMs.set(System.currentTimeMillis());
            try {
                ensureGroup();
                int reclaimed = reclaimStalePending();
                int fresh = readAndPersistNew();
                // If nothing happened and BLOCK returned empty, loop straight
                // back (BLOCK already provided the pause).
                if (reclaimed == 0 && fresh == 0) {
                    Thread.onSpinWait();
                }
            } catch (DataAccessException ex) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break; // interrupt-induced unblock during shutdown — not an error
                }
                groupReady.set(false);
                log.warn("Audit consumer Redis error err={} msg={}; backing off",
                        ex.getClass().getSimpleName(), ex.getMessage());
                if (!sleep(props.getPoll().getErrorBackoffMillis())) {
                    break;
                }
            } catch (RuntimeException ex) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.error("Audit consumer unexpected error; backing off", ex);
                if (!sleep(props.getPoll().getErrorBackoffMillis())) {
                    break;
                }
            }
        }
        log.info("Audit stream consumer loop exited");
    }

    private void ensureGroup() {
        if (groupReady.get()) {
            return;
        }
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        try {
            // MKSTREAM semantics: create the group at offset 0 ($ would skip
            // backlog). Spring's createGroup uses XGROUP CREATE ... MKSTREAM.
            ops.createGroup(props.getStream().getKey(), ReadOffset.from("0"), props.getGroup().getName());
            log.info("Created consumer group {} on stream {}",
                    props.getGroup().getName(), props.getStream().getKey());
        } catch (DataAccessException ex) {
            // BUSYGROUP — group already exists. Any other error rethrows.
            if (!isBusyGroup(ex)) {
                throw ex;
            }
        }
        groupReady.set(true);
    }

    /** XREADGROUP for new (never-delivered) entries: offset {@code >}. */
    private int readAndPersistNew() {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        Consumer consumer = Consumer.from(props.getGroup().getName(), props.getGroup().getConsumer());
        StreamReadOptions readOptions = StreamReadOptions.empty()
                .count(props.getPoll().getBatchSize())
                .block(Duration.ofMillis(props.getPoll().getBlockMillis()));

        List<MapRecord<String, Object, Object>> records = ops.read(consumer, readOptions,
                StreamOffset.create(props.getStream().getKey(), ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return 0;
        }
        for (MapRecord<String, Object, Object> record : records) {
            handleRecord(record);
        }
        return records.size();
    }

    /**
     * XCLAIM this group's entries idle beyond the claim threshold (another
     * consumer crashed mid-persist). Claimed records are persisted + ACKed by
     * this consumer exactly like fresh ones. Bounded per cycle.
     */
    private int reclaimStalePending() {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        var summary = ops.pending(props.getStream().getKey(), props.getGroup().getName());
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return 0;
        }
        var pending = ops.pending(props.getStream().getKey(), props.getGroup().getName(),
                org.springframework.data.domain.Range.unbounded(), props.getPoll().getClaimBatchSize());
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        Duration minIdle = Duration.ofMillis(props.getPoll().getClaimMinIdleMillis());
        RecordId[] toClaim = pending.stream()
                .filter(pm -> pm.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
                .map(pm -> pm.getId())
                .toArray(RecordId[]::new);
        if (toClaim.length == 0) {
            return 0;
        }
        List<MapRecord<String, Object, Object>> claimed = ops.claim(
                props.getStream().getKey(), props.getGroup().getName(), props.getGroup().getConsumer(),
                XClaimOptions.minIdle(minIdle).ids(toClaim));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        for (MapRecord<String, Object, Object> record : claimed) {
            handleRecord(record);
        }
        return claimed.size();
    }

    /** Persist one record then ACK (ACK only after successful/idempotent persist). */
    private void handleRecord(MapRecord<String, Object, Object> record) {
        java.util.Map<String, String> fields = toStringFields(record);
        String entryId = record.getId() == null ? null : record.getId().getValue();
        PersistResult result = persistence.persist(fields, entryId);
        switch (result) {
            case PERSISTED, DUPLICATE, INVALID ->
                    redis.opsForStream().acknowledge(
                            props.getStream().getKey(), props.getGroup().getName(), record.getId());
        }
        if (result == PersistResult.PERSISTED) {
            log.debug("Persisted audit event entryId={} type={}", entryId, fields.get("eventType"));
        }
    }

    private static java.util.Map<String, String> toStringFields(MapRecord<String, Object, Object> record) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        record.getValue().forEach((k, v) ->
                out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return out;
    }

    private boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isBusyGroup(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
