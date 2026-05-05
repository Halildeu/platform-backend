package com.serban.notify.worker;

import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Worker observability metrics (Codex 019dfa47 Q7 PARTIAL absorb).
 *
 * <p>Counters/timers (low-cardinality tags only — no intent_id, recipient_hash):
 * <ul>
 *   <li>{@code notify.worker.cycles{worker, outcome}} — poll cycle counter</li>
 *   <li>{@code notify.worker.claimed{worker}} — claimed-row count per cycle</li>
 *   <li>{@code notify.dispatch.outcome{channel, status}} — dispatch result</li>
 *   <li>{@code notify.retry.scheduled{channel}} — retry scheduling</li>
 *   <li>{@code notify.dlq.terminated{reason}} — DLQ row insert</li>
 *   <li>{@code notify.intent.terminated{terminal}} — intent terminal transition</li>
 *   <li>{@code notify.worker.errors{worker, stage}} — error counter</li>
 *   <li>{@code notify.intent.processing.duration} — timer</li>
 * </ul>
 *
 * <p>Gauges:
 * <ul>
 *   <li>{@code notify.queue.pending.intents} — countByStatus(PENDING)</li>
 *   <li>{@code notify.queue.retry.due} — countByStatus(RETRY)</li>
 * </ul>
 */
@Component
public class WorkerMetrics {

    private final MeterRegistry registry;

    public WorkerMetrics(
        MeterRegistry registry,
        NotificationIntentRepository intentRepo,
        NotificationDeliveryRepository deliveryRepo
    ) {
        this.registry = registry;
        // Gauges — registered once at construction
        registry.gauge("notify.queue.pending.intents", intentRepo,
            r -> r.countByStatus(com.serban.notify.domain.NotificationIntent.Status.PENDING));
        registry.gauge("notify.queue.retry.due", deliveryRepo,
            r -> r.countByStatus(com.serban.notify.domain.NotificationDelivery.Status.RETRY));
    }

    public void cycle(String worker, String outcome) {
        Counter.builder("notify.worker.cycles")
            .tags(Tags.of("worker", worker, "outcome", outcome))
            .register(registry).increment();
    }

    public void claimed(String worker, int amount) {
        Counter.builder("notify.worker.claimed")
            .tags(Tags.of("worker", worker))
            .register(registry).increment(amount);
    }

    public void dispatchOutcome(String channel, String status) {
        Counter.builder("notify.dispatch.outcome")
            .tags(Tags.of("channel", channel, "status", status))
            .register(registry).increment();
    }

    public void retryScheduled(String channel) {
        Counter.builder("notify.retry.scheduled")
            .tags(Tags.of("channel", channel))
            .register(registry).increment();
    }

    public void dlqTerminated(String reason) {
        Counter.builder("notify.dlq.terminated")
            .tags(Tags.of("reason", reason))
            .register(registry).increment();
    }

    public void intentTerminated(String terminal) {
        Counter.builder("notify.intent.terminated")
            .tags(Tags.of("terminal", terminal))
            .register(registry).increment();
    }

    public void error(String worker, String stage) {
        Counter.builder("notify.worker.errors")
            .tags(Tags.of("worker", worker, "stage", stage))
            .register(registry).increment();
    }

    public void recordIntentDuration(Duration duration) {
        Timer.builder("notify.intent.processing.duration")
            .register(registry).record(duration);
    }
}
