package com.example.auditconsumer.consumer;

import com.example.auditconsumer.config.AuditConsumerProperties;
import com.example.auditconsumer.service.AuditEventPersistenceService;
import com.example.auditconsumer.service.AuditEventPersistenceService.PersistOutcome;
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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 *   <li>persist each record (immutable + hash-chained), then atomically
 *       {@code XACK + XDEL} the transient source entry — ACK strictly AFTER a successful persist, so a crash before persist
 *       leaves the entry in the PEL for redelivery (no audit loss);</li>
 *   <li>duplicates are ACKed; malformed/conflicting records are written to a
 *       bounded, expiring, PII-minimal DLQ before atomic ACK+delete so they do not wedge the
 *       PEL or disappear silently.</li>
 * </ol>
 *
 * <p>Active only when {@code audit.consumer.enabled=true} (default). Persistence
 * + the {@code audit_event} schema exist regardless of this flag, so a unit
 * test can wire the persistence path without a Redis dependency.
 * The source stream is a transient transport owned by this one authoritative
 * consumer group; durable replay comes from PostgreSQL/outbox, not a second
 * Redis consumer group. This ownership permits source {@code XDEL} after ACK.
 *
 * <p>Graceful shutdown: {@link #stop()} flips the run flag and interrupts the
 * blocked {@code XREADGROUP}; the loop drains and exits.
 */
@Component
@ConditionalOnProperty(name = "audit.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class AuditStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditStreamConsumer.class);
    private static final DefaultRedisScript<Long> ACK_AND_DELETE_SCRIPT = new DefaultRedisScript<>("""
            local acknowledged = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            if acknowledged == 1 then
              redis.call('XDEL', KEYS[1], ARGV[2])
            end
            return acknowledged
            """, Long.class);
    private static final DefaultRedisScript<Long> DLQ_ACK_AND_DELETE_SCRIPT = new DefaultRedisScript<>("""
            local dlqId = redis.call('XADD', KEYS[1], 'MAXLEN', '~', ARGV[1], '*',
              '_dlqReason', ARGV[2],
              '_dlqSourceEntryId', ARGV[3],
              '_dlqSourceStream', KEYS[2],
              '_dlqAtMs', ARGV[4],
              '_dlqEventType', ARGV[5],
              '_dlqFingerprint', ARGV[6])
            redis.call('EXPIRE', KEYS[1], ARGV[7])
            local acknowledged = redis.call('XACK', KEYS[2], ARGV[8], ARGV[3])
            if acknowledged == 1 then
              redis.call('XDEL', KEYS[2], ARGV[3])
            end
            return acknowledged
            """, Long.class);

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
        validateOperationalProperties(props);
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
            handleRecord(record, 1L);
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
        Map<String, Long> nextDeliveryAttemptById = new java.util.HashMap<>();
        RecordId[] toClaim = pending.stream()
                .filter(pm -> pm.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
                .peek(pm -> nextDeliveryAttemptById.put(
                        pm.getId().getValue(), Math.max(2L, pm.getTotalDeliveryCount() + 1L)))
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
            handleRecord(record, nextDeliveryAttemptById.getOrDefault(
                    record.getId().getValue(), 2L));
        }
        return claimed.size();
    }

    /**
     * Persist one record then ACK. ACK semantics (no audit loss):
     * <ul>
     *   <li>PERSISTED / DUPLICATE → ACK (the row is durably stored / already was);</li>
     *   <li>RETRYABLE → keep pending until a bounded delivery count, then park a
     *       PII-minimal diagnostic in the DLQ and ACK;</li>
     *   <li>INVALID/CONFLICT → XADD a PII-minimal fingerprinted diagnostic to the DLQ, and ACK
     *       ONLY if the DLQ write succeeds. A failed DLQ write leaves the entry
     *       in the PEL (no ACK) so the poison event is never lost;</li>
     *   <li>a non-dedup integrity violation propagates out of
     *       {@code persist(...)} → caught here, NOT ACKed → stays in the PEL for
     *       redelivery (per-record isolation: one bad record does not abort the
     *       batch or wedge the others).</li>
     * </ul>
     */
    private void handleRecord(MapRecord<String, Object, Object> record, long deliveryAttempt) {
        java.util.Map<String, String> fields = toStringFields(record);
        String entryId = record.getId() == null ? null : record.getId().getValue();

        final PersistOutcome outcome;
        try {
            outcome = persistence.persist(fields, entryId);
        } catch (DataAccessException ex) {
            // Non-dedup integrity / DB error: do NOT ACK — leave it in the PEL so
            // a transient DB issue redelivers rather than dropping the event.
            log.warn("Audit persist failed (left unacked for redelivery) entryId={} err={} msg={}",
                    entryId, ex.getClass().getSimpleName(), ex.getMessage());
            return;
        }

        switch (outcome.result()) {
            case PERSISTED -> {
                ackAndDelete(record);
                log.debug("Persisted audit event entryId={} type={}", entryId, fields.get("eventType"));
            }
            case DUPLICATE -> ackAndDelete(record);
            case RETRYABLE -> {
                if (deliveryAttempt >= props.getPoll().getDependencyMaxDeliveryAttempts()) {
                    routeToDlqAndAck(fields, entryId, outcome.reason());
                } else {
                    log.info("Audit dependency pending; leaving entry unacked entryId={} attempt={}",
                            entryId, deliveryAttempt);
                }
            }
            case INVALID, CONFLICT -> {
                routeToDlqAndAck(fields, entryId, outcome.reason());
            }
        }
    }

    private void ackAndDelete(MapRecord<String, Object, Object> record) {
        Long acknowledged = redis.execute(
                ACK_AND_DELETE_SCRIPT,
                List.of(props.getStream().getKey()),
                props.getGroup().getName(),
                record.getId().getValue());
        if (acknowledged == null || acknowledged != 1L) {
            throw new IllegalStateException(
                    "Audit source entry was not acknowledged: " + record.getId().getValue());
        }
    }

    /**
     * XADD a PII-minimal diagnostic to the dead-letter stream. Raw source fields
     * and parse messages are deliberately excluded; a deterministic fingerprint
     * permits correlation with secured source evidence without duplicating
     * identity-bearing values. The stream has bounded length and a key TTL.
     */
    private boolean routeToDlqAndAck(
            java.util.Map<String, String> sourceFields,
            String entryId,
            String reason) {
        try {
            if (entryId == null || entryId.isBlank()) {
                log.error("DLQ route rejected because source entry id is absent");
                return false;
            }
            Long acknowledged = redis.execute(
                    DLQ_ACK_AND_DELETE_SCRIPT,
                    List.of(props.getDlqStreamKey(), props.getStream().getKey()),
                    Long.toString(props.getDlqMaxLen()),
                    safeReason(reason),
                    entryId,
                    Long.toString(System.currentTimeMillis()),
                    safeEventType(sourceFields.get("eventType")),
                    fingerprint(sourceFields),
                    Long.toString(props.getDlqTtlSeconds()),
                    props.getGroup().getName());
            if (acknowledged == null || acknowledged != 1L) {
                log.error("DLQ atomic route did not acknowledge source entryId={}", entryId);
                return false;
            }
            log.warn("Routed audit diagnostic to DLQ stream={} sourceEntryId={} reason={}",
                    props.getDlqStreamKey(), entryId, safeReason(reason));
            return true;
        } catch (DataAccessException ex) {
            log.error("DLQ atomic route failed stream={} sourceEntryId={} err={} msg={}",
                    props.getDlqStreamKey(), entryId, ex.getClass().getSimpleName(), ex.getMessage());
            return false;
        }
    }

    private static final Set<String> SAFE_REASONS = Set.of(
            "INVALID_EVENT", "IDEMPOTENCY_CONFLICT", "CONSENT_DEPENDENCY_PENDING");
    private static final Set<String> SAFE_EVENT_TYPES = Set.of(
            "CHUNK_ADMISSION_REJECTED",
            "RECORDING_CONSENT_GRANTED",
            "RECORDING_CONSENT_REVOKED");

    private static String safeReason(String reason) {
        return reason != null && SAFE_REASONS.contains(reason) ? reason : "UNCLASSIFIED";
    }

    private static String safeEventType(String eventType) {
        return eventType != null && SAFE_EVENT_TYPES.contains(eventType) ? eventType : "UNKNOWN";
    }

    private static String fingerprint(Map<String, String> sourceFields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(sourceFields).forEach((key, value) -> {
                updateDigest(digest, key);
                updateDigest(digest, value == null ? "" : value);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void validateOperationalProperties(AuditConsumerProperties properties) {
        if (properties.getDlqStreamKey() == null || properties.getDlqStreamKey().isBlank()) {
            throw new IllegalArgumentException("audit.consumer.dlq-stream-key must not be blank");
        }
        if (properties.getDlqMaxLen() <= 0) {
            throw new IllegalArgumentException("audit.consumer.dlq-max-len must be > 0");
        }
        if (properties.getDlqTtlSeconds() <= 0) {
            throw new IllegalArgumentException("audit.consumer.dlq-ttl-seconds must be > 0");
        }
        if (properties.getPoll().getDependencyMaxDeliveryAttempts() <= 0) {
            throw new IllegalArgumentException(
                    "audit.consumer.poll.dependency-max-delivery-attempts must be > 0");
        }
        if (properties.getHealth().getMaxPendingForHealthy() < 0) {
            throw new IllegalArgumentException(
                    "audit.consumer.health.max-pending-for-healthy must be >= 0");
        }
        if (properties.getHealth().getMaxStreamLengthForHealthy() < 0) {
            throw new IllegalArgumentException(
                    "audit.consumer.health.max-stream-length-for-healthy must be >= 0");
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
