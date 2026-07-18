package com.example.transcript.directstt;

import com.example.transcript.directstt.DirectSttTranscriptResultHandler.HandleOutcome;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Default-off Redis Streams consumer for audio-gateway direct-STT transcript
 * results. ACK happens only after transcript-service upsert succeeds; malformed
 * events are parked in a metadata-only DLQ before ACK to avoid wedging the PEL
 * without copying transcript text into diagnostics.
 */
@Component
@ConditionalOnProperty(name = "transcript.direct-stt-result-consumer.enabled", havingValue = "true")
public class DirectSttTranscriptResultStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(DirectSttTranscriptResultStreamConsumer.class);

    private final StringRedisTemplate redis;
    private final DirectSttTranscriptResultHandler handler;
    private final DirectSttTranscriptResultConsumerProperties props;
    private final MeterRegistry meters;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean groupReady = new AtomicBoolean(false);
    private volatile Thread loopThread;

    @Autowired
    public DirectSttTranscriptResultStreamConsumer(
            StringRedisTemplate redis,
            DirectSttTranscriptResultHandler handler,
            DirectSttTranscriptResultConsumerProperties props,
            MeterRegistry meters) {
        this.redis = redis;
        this.handler = handler;
        this.props = props;
        this.meters = meters;
    }

    DirectSttTranscriptResultStreamConsumer(
            StringRedisTemplate redis,
            DirectSttTranscriptResultHandler handler,
            DirectSttTranscriptResultConsumerProperties props) {
        this(redis, handler, props, Metrics.globalRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running.compareAndSet(false, true)) {
            loopThread = new Thread(this::runLoop, "direct-stt-transcript-consumer");
            loopThread.setDaemon(true);
            loopThread.start();
            log.info("Direct-STT transcript consumer started stream={} group={} consumer={}",
                    props.getStream().getKey(), props.getGroup().getName(), props.getGroup().getConsumer());
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
                log.warn("Direct-STT transcript Redis error err={} msg={}; backing off",
                        ex.getClass().getSimpleName(), ex.getMessage());
                if (!sleep(props.getPoll().getErrorBackoffMillis())) {
                    break;
                }
            } catch (RuntimeException ex) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.warn("Direct-STT transcript consumer error err={} msg={}; backing off",
                        ex.getClass().getSimpleName(), ex.getMessage());
                if (!sleep(props.getPoll().getErrorBackoffMillis())) {
                    break;
                }
            }
        }
        log.info("Direct-STT transcript consumer loop exited");
    }

    private void ensureGroup() {
        if (groupReady.get()) {
            return;
        }
        StreamOperations<String, Object, Object> ops = redis.opsForStream();
        try {
            ops.createGroup(props.getStream().getKey(), ReadOffset.from("0"), props.getGroup().getName());
            log.info("Created direct-STT transcript consumer group {} on stream {}",
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
        HandleOutcome outcome = handler.handle(fields, entryId);
        switch (outcome.result()) {
            case PROCESSED -> {
                ack(record);
                meters.counter("transcript_direct_stt_processed_total").increment();
                log.debug("Direct-STT transcript routed entryId={} sessionId={} chunkSeq={}",
                        entryId, fields.get("sessionId"), fields.get("chunkSeq"));
            }
            case PENDING -> {
                meters.counter("transcript_direct_stt_pending_total", "reason", safeReason(outcome.reason()))
                        .increment();
                log.info("Direct-STT transcript pending canonical session mapping entryId={} sessionId={} reason={}",
                        entryId, fields.get("sessionId"), safeReason(outcome.reason()));
            }
            case DEAD -> {
                meters.counter("transcript_direct_stt_dead_total", "reason", safeReason(outcome.reason()))
                        .increment();
                if (writeMetadataOnlyDlq(fields, entryId, safeReason(outcome.reason()))) {
                    ack(record);
                } else {
                    log.error("Direct-STT transcript dead mapping DLQ write failed; leaving entry unacked entryId={}",
                            entryId);
                }
            }
            case INVALID -> {
                meters.counter("transcript_direct_stt_invalid_total", "reason", safeReason(outcome.reason()))
                        .increment();
                if (writeMetadataOnlyDlq(fields, entryId, outcome.reason())) {
                    ack(record);
                } else {
                    log.error("Direct-STT transcript DLQ write failed; leaving entry unacked entryId={}", entryId);
                }
            }
        }
    }

    private void ack(MapRecord<String, ?, ?> record) {
        redis.opsForStream().acknowledge(
                props.getStream().getKey(), props.getGroup().getName(), record.getId());
    }

    private boolean writeMetadataOnlyDlq(Map<String, String> sourceFields, String entryId, String reason) {
        try {
            Map<String, String> dlq = new LinkedHashMap<>();
            copyIfPresent(sourceFields, dlq, "schemaVersion");
            copyIfPresent(sourceFields, dlq, "eventType");
            copyIfPresent(sourceFields, dlq, "sessionId");
            copyIfPresent(sourceFields, dlq, "tenantId");
            copyIfPresent(sourceFields, dlq, "meetingId");
            copyIfPresent(sourceFields, dlq, "chunkSeq");
            copyIfPresent(sourceFields, dlq, "chunkStartedAtMs");
            copyIfPresent(sourceFields, dlq, "correlationId");
            copyIfPresent(sourceFields, dlq, "sha256");
            copyIfPresent(sourceFields, dlq, "textLength");
            copyIfPresent(sourceFields, dlq, "status");
            copyIfPresent(sourceFields, dlq, "receivedAtMs");
            dlq.put("_dlqReason", reason == null ? "INVALID" : reason);
            dlq.put("_dlqSourceEntryId", entryId == null ? "" : entryId);
            dlq.put("_dlqSourceStream", props.getStream().getKey());
            dlq.put("_dlqAtMs", Long.toString(System.currentTimeMillis()));
            redis.opsForStream().add(StreamRecords.mapBacked(dlq).withStreamKey(props.getDlqStreamKey()));
            log.warn("Direct-STT transcript poison event routed to metadata-only DLQ stream={} entryId={} reason={}",
                    props.getDlqStreamKey(), entryId, reason);
            return true;
        } catch (DataAccessException ex) {
            log.error("Direct-STT transcript DLQ XADD failed stream={} entryId={} err={} msg={}",
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
            return "UNSPECIFIED";
        }
        String normalized = reason.replaceAll("[^A-Za-z0-9_]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 64));
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
