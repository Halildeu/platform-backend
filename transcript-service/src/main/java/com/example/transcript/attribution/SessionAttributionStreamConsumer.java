package com.example.transcript.attribution;

import com.example.transcript.service.SessionErasureFence.SessionErasedException;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Default-off Redis Streams consumer for live-stt post-session attribution events
 * (#3746 BE-D4b; producer contract directSttSessionAttribution.v1). Clone of the
 * direct-STT result consumer's PEL discipline: ACK only after the apply transaction
 * commits; malformed events go to a metadata-only DLQ before ACK; an erased session
 * ACKs as ignored (erasure won, attribution must not resurrect anything).
 */
@Component
@ConditionalOnProperty(name = "transcript.session-attribution-consumer.enabled", havingValue = "true")
public class SessionAttributionStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(SessionAttributionStreamConsumer.class);

    private final StringRedisTemplate redis;
    private final SessionAttributionApplyService applyService;
    private final SessionAttributionConsumerProperties props;
    private final MeterRegistry meters;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean groupReady = new AtomicBoolean(false);
    private volatile Thread loopThread;

    public SessionAttributionStreamConsumer(
            StringRedisTemplate redis,
            SessionAttributionApplyService applyService,
            SessionAttributionConsumerProperties props,
            MeterRegistry meters) {
        this.redis = redis;
        this.applyService = applyService;
        this.props = props;
        this.meters = meters;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running.compareAndSet(false, true)) {
            loopThread = new Thread(this::runLoop, "session-attribution-consumer");
            loopThread.setDaemon(true);
            loopThread.start();
            log.info("Session attribution consumer started stream={} group={} consumer={}",
                    props.getStream().getKey(), props.getGroup().getName(),
                    props.getGroup().getConsumer());
        }
    }

    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
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

    boolean isGroupReady() {
        return groupReady.get();
    }

    private void runLoop() {
        while (running.get()) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                ensureGroup();
                int reclaimed = reclaimStalePending();
                int fresh = readNew();
                if (reclaimed == 0 && fresh == 0) {
                    Thread.onSpinWait();
                }
            } catch (DataAccessException ex) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                groupReady.set(false);
                log.warn("Session attribution Redis error err={} msg={}; backing off",
                        ex.getClass().getSimpleName(), ex.getMessage());
                if (!sleep(props.getPoll().getErrorBackoffMillis())) {
                    break;
                }
            } catch (RuntimeException ex) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.warn("Session attribution consumer error err={} msg={}; backing off",
                        ex.getClass().getSimpleName(), ex.getMessage());
                if (!sleep(props.getPoll().getErrorBackoffMillis())) {
                    break;
                }
            }
        }
        log.info("Session attribution consumer loop exited");
    }

    private void ensureGroup() {
        if (groupReady.get()) {
            return;
        }
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        try {
            ops.createGroup(props.getStream().getKey(), ReadOffset.from("0"),
                    props.getGroup().getName());
            log.info("Created session attribution consumer group {} on stream {}",
                    props.getGroup().getName(), props.getStream().getKey());
        } catch (DataAccessException ex) {
            if (!isBusyGroup(ex)) {
                throw ex;
            }
        }
        groupReady.set(true);
    }

    private int readNew() {
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
        records.forEach(this::handleRecord);
        return records.size();
    }

    private int reclaimStalePending() {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        var summary = ops.pending(props.getStream().getKey(), props.getGroup().getName());
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return 0;
        }
        var pending = ops.pending(props.getStream().getKey(), props.getGroup().getName(),
                org.springframework.data.domain.Range.unbounded(),
                props.getPoll().getClaimBatchSize());
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
                props.getStream().getKey(),
                props.getGroup().getName(),
                props.getGroup().getConsumer(),
                XClaimOptions.minIdle(minIdle).ids(toClaim));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        claimed.forEach(this::handleRecord);
        return claimed.size();
    }

    void handleRecord(MapRecord<String, ?, ?> record) {
        Map<String, String> fields = toStringFields(record);
        String entryId = record.getId() == null ? null : record.getId().getValue();
        final SessionAttributionEvent event;
        try {
            event = SessionAttributionEvent.parse(fields.get("payload"));
        } catch (IllegalArgumentException ex) {
            meters.counter("transcript_session_attribution_invalid_total").increment();
            if (writeMetadataOnlyDlq(fields, entryId, ex.getMessage())) {
                ack(record);
            } else {
                log.error("Session attribution DLQ write failed; leaving entry unacked entryId={}",
                        entryId);
            }
            return;
        }
        try {
            var outcome = applyService.apply(event);
            ack(record);
            meters.counter("transcript_session_attribution_processed_total").increment();
            meters.counter("transcript_session_attribution_windows_applied_total")
                    .increment(outcome.applied());
            meters.counter("transcript_session_attribution_windows_missing_total")
                    .increment(outcome.missingWindows());
        } catch (SessionErasedException ex) {
            // Erasure is authoritative: attribution for an erased session is dropped.
            ack(record);
            meters.counter("transcript_session_attribution_erased_total").increment();
            log.info("Session attribution dropped for erased session entryId={} meetingId={}",
                    entryId, fields.get("meetingId"));
        }
        // Any other runtime/data-access failure propagates: the entry stays in the PEL
        // and is re-delivered/reclaimed — apply is idempotent (first write wins).
    }

    private void ack(MapRecord<String, ?, ?> record) {
        redis.opsForStream().acknowledge(
                props.getStream().getKey(), props.getGroup().getName(), record.getId());
    }

    private boolean writeMetadataOnlyDlq(Map<String, String> sourceFields, String entryId, String reason) {
        try {
            Map<String, String> dlq = new LinkedHashMap<>();
            copyIfPresent(sourceFields, dlq, "schema");
            copyIfPresent(sourceFields, dlq, "meetingId");
            dlq.put("_dlqReason", safeReason(reason));
            dlq.put("_dlqSourceEntryId", entryId == null ? "" : entryId);
            dlq.put("_dlqSourceStream", props.getStream().getKey());
            dlq.put("_dlqAtMs", Long.toString(System.currentTimeMillis()));
            redis.opsForStream().add(StreamRecords.mapBacked(dlq).withStreamKey(props.getDlqStreamKey()));
            log.warn("Session attribution poison event routed to metadata-only DLQ stream={} entryId={} reason={}",
                    props.getDlqStreamKey(), entryId, safeReason(reason));
            return true;
        } catch (DataAccessException ex) {
            log.error("Session attribution DLQ XADD failed stream={} entryId={} err={} msg={}",
                    props.getDlqStreamKey(), entryId, ex.getClass().getSimpleName(), ex.getMessage());
            return false;
        }
    }

    private static void copyIfPresent(Map<String, String> source, Map<String, String> target, String key) {
        String value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "INVALID";
        }
        String normalized = reason.replaceAll("[^A-Za-z0-9_ :.-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 120));
    }

    private static Map<String, String> toStringFields(MapRecord<String, ?, ?> record) {
        Map<String, String> out = new LinkedHashMap<>();
        record.getValue().forEach((key, value) ->
                out.put(String.valueOf(key), value == null ? null : String.valueOf(value)));
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
