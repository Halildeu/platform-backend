package com.example.transcript.notify;

import com.example.transcript.events.TranscriptMeetingEventMessage;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent durable HTTP delivery. Never publishes Redis events or consumes their retry budget. */
@Component
@ConditionalOnProperty(prefix="transcript.notify", name="enabled", havingValue="true")
public class NotificationDeliveryQueue {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NotificationDeliveryQueue.class);
    private final TranscriptEventOutboxRepository repository;
    private final TransactionTemplate transaction;
    private final TranscriptReadyNotificationSink sink;
    private final boolean scheduled;
    private final int maxAttempts;
    private ScheduledExecutorService executor;

    public NotificationDeliveryQueue(TranscriptEventOutboxRepository repository, PlatformTransactionManager manager,
            TranscriptReadyNotificationSink sink,
            @Value("${transcript.notify.scheduling-enabled:true}") boolean scheduled,
            @Value("${transcript.notify.max-attempts:20}") int maxAttempts) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.sink = sink;
        this.scheduled = scheduled;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** Called only after a successful fence, in the domain transition's transaction. */
    public void enqueue(UUID id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Notification handoff requires a transaction");
        repository.enqueueNotification(id);
    }

    @PostConstruct
    void start() {
        if (!scheduled) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "transcript-notification-delivery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try { runOne(); }
            catch (RuntimeException ex) { LOG.warn("Notification cycle failed cause={}", ex.getClass().getSimpleName()); }
        }, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() { if (executor != null) executor.shutdownNow(); }

    public void runOne() {
        transaction.executeWithoutResult(status -> {
            var pending = repository.lockNextNotification();
            if (pending.isEmpty()) return;
            var row = pending.get();
            if (repository.lockNotificationSource(row.getId()).isEmpty()
                    || repository.notificationErasureRequested(row.getId())) {
                repository.notificationSuppressed(row.getId());
                return;
            }
            var message = TranscriptMeetingEventMessage.from(row);
            try {
                sink.deliver(message);
            } catch (RuntimeException ex) {
                repository.notificationFailed(row.getId(), maxAttempts, Instant.now().plusSeconds(30),
                        ex.getClass().getSimpleName());
                return;
            }
            repository.notificationDelivered(row.getId());
        });
    }
}
