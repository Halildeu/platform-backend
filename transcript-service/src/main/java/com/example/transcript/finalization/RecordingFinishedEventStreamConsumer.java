package com.example.transcript.finalization;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Default-off meeting-event consumer that enrolls recording-finished events in
 * the restart-safe transcript finalization state machine. Poison records are
 * ACKed only after a metadata-only DLQ write succeeds.
 */
@Component
@ConditionalOnProperty(
        name = "transcript.finalization.recording-finished-consumer.enabled",
        havingValue = "true")
public class RecordingFinishedEventStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(RecordingFinishedEventStreamConsumer.class);

    private final StringRedisTemplate redis;
    private final RecordingFinishedEventHandler handler;
    private final TranscriptFinalizationProperties.RecordingFinishedConsumer props;
    private final MeterRegistry meters;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean groupReady = new AtomicBoolean(false);
    private volatile Thread loopThread;

    @Autowired
    public RecordingFinishedEventStreamConsumer(
            StringRedisTemplate redis,
            RecordingFinishedEventHandler handler,
            TranscriptFinalizationProperties properties,
            MeterRegistry meters) {
        this(redis, handler, properties.getRecordingFinishedConsumer(), meters);
    }

    RecordingFinishedEventStreamConsumer(
            StringRedisTemplate redis,
            RecordingFinishedEventHandler handler,
            TranscriptFinalizationProperties.RecordingFinishedConsumer props) {
        this(redis, handler, props, Metrics.globalRegistry);
    }

    private RecordingFinishedEventStreamConsumer(
            StringRedisTemplate redis,
            RecordingFinishedEventHandler handler,
            TranscriptFinalizationProperties.RecordingFinishedConsumer props,
            MeterRegistry meters) {
        this.redis = redis;
        this.handler = handler;
        this.props = props;
        this.meters = meters;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running.compareAndSet(false, true)) {
            loopThread = new Thread(this::runLoop, "recording-finished-transcript-consumer");
            loopThread.setDaemon(true);
            loopThread.start();
            log.info("Recording-finished consumer started stream={} group={} consumer={}",
                    props.getStreamKey(), props.getGroupName(), props.getConsumerName());
        }
    }

    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
            Thread thread = loopThread;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(Duration.ofSeconds(10).toMillis());
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
        while (running.get() && !Thread.currentThread().isInterrupted()) {
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
                log.warn("Recording-finished Redis error err={}; backing off",
                        ex.getClass().getSimpleName());
                if (!sleep(props.getErrorBackoffMillis())) {
                    break;
                }
            } catch (RuntimeException ex) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.warn("Recording-finished consumer error err={}; backing off",
                        ex.getClass().getSimpleName());
                if (!sleep(props.getErrorBackoffMillis())) {
                    break;
                }
            }
        }
        log.info("Recording-finished consumer loop exited");
    }

    private void ensureGroup() {
        if (groupReady.get()) {
            return;
        }
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        try {
            ops.createGroup(props.getStreamKey(), ReadOffset.from("0"), props.getGroupName());
            log.info("Created recording-finished consumer group={} stream={}",
                    props.getGroupName(), props.getStreamKey());
        } catch (DataAccessException ex) {
            if (!isBusyGroup(ex)) {
                throw ex;
            }
        }
        groupReady.set(true);
    }

    private int readNew() {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        Consumer consumer = Consumer.from(props.getGroupName(), props.getConsumerName());
        StreamReadOptions options = StreamReadOptions.empty()
                .count(props.getBatchSize())
                .block(Duration.ofMillis(props.getBlockMillis()));
        List<MapRecord<String, Object, Object>> records = ops.read(
                consumer, options,
                StreamOffset.create(props.getStreamKey(), ReadOffset.lastConsumed()));
        if (records == null || records.isEmpty()) {
            return 0;
        }
        records.forEach(this::handleRecord);
        return records.size();
    }

    private int reclaimStalePending() {
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        var summary = ops.pending(props.getStreamKey(), props.getGroupName());
        if (summary == null || summary.getTotalPendingMessages() == 0) {
            return 0;
        }
        var pending = ops.pending(
                props.getStreamKey(), props.getGroupName(),
                org.springframework.data.domain.Range.unbounded(), props.getClaimBatchSize());
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        Duration minIdle = Duration.ofMillis(props.getClaimMinIdleMillis());
        RecordId[] ids = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
                .map(message -> message.getId())
                .toArray(RecordId[]::new);
        if (ids.length == 0) {
            return 0;
        }
        List<MapRecord<String, Object, Object>> claimed = ops.claim(
                props.getStreamKey(), props.getGroupName(), props.getConsumerName(),
                XClaimOptions.minIdle(minIdle).ids(ids));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        claimed.forEach(this::handleRecord);
        return claimed.size();
    }

    void handleRecord(MapRecord<String, ?, ?> record) {
        Map<String, String> fields = toStringFields(record);
        String entryId = record.getId() == null ? null : record.getId().getValue();
        RecordingFinishedEventHandler.HandleOutcome outcome = handler.handle(fields);
        String reason = safeReason(outcome.reason());
        switch (outcome.result()) {
            case PROCESSED -> {
                acknowledge(record);
                meters.counter("transcript_recording_finished_processed_total").increment();
            }
            case DUPLICATE -> {
                acknowledge(record);
                meters.counter("transcript_recording_finished_duplicate_total").increment();
            }
            case IGNORED -> {
                acknowledge(record);
                meters.counter("transcript_recording_finished_ignored_total").increment();
            }
            case INVALID, DEAD -> {
                meters.counter("transcript_recording_finished_poison_total",
                        "result", outcome.result().name().toLowerCase(), "reason", reason).increment();
                if (writeMetadataOnlyDlq(fields, entryId, outcome.result().name(), reason)) {
                    acknowledge(record);
                } else {
                    log.error("Recording-finished DLQ write failed; leaving event unacked entryId={}", entryId);
                }
            }
        }
    }

    private void acknowledge(MapRecord<String, ?, ?> record) {
        redis.opsForStream().acknowledge(props.getStreamKey(), props.getGroupName(), record.getId());
    }

    private boolean writeMetadataOnlyDlq(
            Map<String, String> source, String entryId, String result, String reason) {
        try {
            Map<String, String> dlq = new LinkedHashMap<>();
            copyIfPresent(source, dlq, "schemaVersion");
            copyIfPresent(source, dlq, "eventType");
            copyIfPresent(source, dlq, "producer");
            copyIfPresent(source, dlq, "aggregateType");
            copyIfPresent(source, dlq, "aggregateId");
            copyIfPresent(source, dlq, "aggregateRevision");
            copyIfPresent(source, dlq, "eventKey");
            copyIfPresent(source, dlq, "meetingId");
            copyIfPresent(source, dlq, "tenantId");
            copyIfPresent(source, dlq, "orgId");
            copyIfPresent(source, dlq, "occurredAt");
            String payload = source.get("payload");
            if (payload != null) {
                dlq.put("_payloadSha256", sha256(payload));
                dlq.put("_payloadUtf8Bytes", Integer.toString(payload.getBytes(StandardCharsets.UTF_8).length));
            }
            dlq.put("_dlqResult", result);
            dlq.put("_dlqReason", reason);
            dlq.put("_dlqSourceEntryId", entryId == null ? "" : entryId);
            dlq.put("_dlqSourceStream", props.getStreamKey());
            dlq.put("_dlqAtMs", Long.toString(System.currentTimeMillis()));
            RecordId dlqId = redis.opsForStream().add(
                    StreamRecords.mapBacked(dlq).withStreamKey(props.getDlqStreamKey()));
            if (dlqId == null) {
                log.error("Recording-finished DLQ XADD returned no id entryId={}", entryId);
                return false;
            }
            log.warn("Recording-finished poison event routed to metadata-only DLQ entryId={} result={} reason={}",
                    entryId, result, reason);
            return true;
        } catch (DataAccessException ex) {
            log.error("Recording-finished DLQ XADD failed entryId={} err={}",
                    entryId, ex.getClass().getSimpleName());
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
            return "UNSPECIFIED";
        }
        String normalized = reason.replaceAll("[^A-Za-z0-9_]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 64));
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static Map<String, String> toStringFields(MapRecord<String, ?, ?> record) {
        Map<String, String> result = new LinkedHashMap<>();
        record.getValue().forEach((key, value) ->
                result.put(String.valueOf(key), value == null ? null : String.valueOf(value)));
        return result;
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isBusyGroup(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
