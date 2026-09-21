package com.example.meeting.notify;

import com.example.meeting.events.MeetingEventMessage;
import com.example.meeting.repository.MeetingEventOutboxRepository;
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
@ConditionalOnProperty(prefix="meeting.notify", name="enabled", havingValue="true")
public class NotificationDeliveryQueue {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NotificationDeliveryQueue.class);
    private final MeetingEventOutboxRepository repository;
    private final TransactionTemplate transaction;
    private final AssignmentNotificationSink assignment; private final org.springframework.beans.factory.ObjectProvider<SummaryReadyNotificationSink> summary;
    private final boolean scheduled;
    private final int maxAttempts;
    private ScheduledExecutorService executor;

    public NotificationDeliveryQueue(MeetingEventOutboxRepository repository, PlatformTransactionManager manager,
            AssignmentNotificationSink assignment, org.springframework.beans.factory.ObjectProvider<SummaryReadyNotificationSink> summary,
            @Value("${meeting.notify.scheduling-enabled:true}") boolean scheduled,
            @Value("${meeting.notify.max-attempts:20}") int maxAttempts) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.assignment = assignment; this.summary = summary;
        this.scheduled = scheduled;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** Called only after a successful fence, in the domain transition's transaction. */
    public void enqueue(UUID id) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Notification handoff requires a transaction");
        repository.enqueueNotification(id, summary.getIfAvailable() != null);
    }

    @PostConstruct
    void start() {
        if (!scheduled) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "meeting-notification-delivery");
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
            var pending = repository.lockNextNotification(summary.getIfAvailable() != null);
            if (pending.isEmpty()) return;
            var row = pending.get();
            if ("meeting.summary.ready".equals(row.getEventType())
                    && (repository.findNotificationSource(row.getId()).isEmpty()
                        || repository.notificationErasureRequested(row.getId()))) {
                repository.notificationSuppressed(row.getId());
                return;
            }
            var message = MeetingEventMessage.from(row);
            try {
                if (assignment.handles(message.eventType())) assignment.deliver(message);
                else {
                    var sink = summary.getIfAvailable();
                    if (sink == null) throw new IllegalStateException("Summary delivery disabled");
                    sink.deliver(message);
                }
            } catch (RuntimeException ex) {
                repository.notificationFailed(row.getId(), maxAttempts, Instant.now().plusSeconds(30),
                        ex.getClass().getSimpleName());
                return;
            }
            repository.notificationDelivered(row.getId());
        });
    }
}
