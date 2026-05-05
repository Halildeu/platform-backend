package com.serban.notify.worker;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.DeadLetter;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.DeadLetterRepository;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeadLetterService — exhausted RETRY → DLQ + delivery FAILED + intent terminal
 * (Codex 019dfa47 Q5 AGREE absorb).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>RETRY delivery {@code attempt_count >= max-attempts} → terminate
 *       delivery FAILED, set {@code permanent_failure_at}</li>
 *   <li>INSERT dead_letter row (idempotent: unique active index
 *       {@code WHERE replayed=FALSE} engelse — duplicate insert OK)</li>
 *   <li>Audit DLQ_TERMINATED event</li>
 *   <li>Caller (RetryWorker veya OutboxPoller) intent terminal status
 *       resolution çağırır</li>
 * </ol>
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterRepository dlqRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final NotificationIntentRepository intentRepo;
    private final IntentStatusResolver statusResolver;
    private final AuditEventPublisher audit;
    private final WorkerMetrics metrics;

    public DeadLetterService(
        DeadLetterRepository dlqRepo,
        NotificationDeliveryRepository deliveryRepo,
        NotificationIntentRepository intentRepo,
        IntentStatusResolver statusResolver,
        AuditEventPublisher audit,
        WorkerMetrics metrics
    ) {
        this.dlqRepo = dlqRepo;
        this.deliveryRepo = deliveryRepo;
        this.intentRepo = intentRepo;
        this.statusResolver = statusResolver;
        this.audit = audit;
        this.metrics = metrics;
    }

    /**
     * Move exhausted delivery to DLQ + terminate delivery + recompute intent.
     *
     * @param delivery RETRY delivery whose attempt_count >= max-attempts
     * @param reason DLQ reason ({@code "max_attempts"}, {@code "expired"})
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void moveToDlq(NotificationDelivery delivery, String reason) {
        OffsetDateTime now = OffsetDateTime.now();

        // 1) DLQ row insert (idempotent via unique active index)
        DeadLetter dl = new DeadLetter();
        dl.setIntentId(delivery.getIntentId());
        dl.setDeliveryId(delivery.getId());
        dl.setChannel(delivery.getChannel());
        dl.setRecipientHash(delivery.getRecipientHash());
        dl.setProvider(delivery.getProvider());
        dl.setAttemptCount(delivery.getAttemptCount());
        dl.setLastFailureReason(delivery.getFailureReason());
        dl.setLastFailureAt(delivery.getLastAttemptAt() != null ? delivery.getLastAttemptAt() : now);
        try {
            dlqRepo.saveAndFlush(dl);
        } catch (DataIntegrityViolationException e) {
            // Duplicate active DLQ for this delivery — already moved by another
            // worker pod cycle; idempotent OK.
            log.info("DLQ already exists (idempotent skip): deliveryId={} reason={}",
                delivery.getId(), reason);
            return;
        }

        // 2) Terminate delivery FAILED
        delivery.setStatus(NotificationDelivery.Status.FAILED);
        delivery.setPermanentFailureAt(now);
        delivery.setProcessingLeaseUntil(null);
        deliveryRepo.save(delivery);

        // 3) Resolve intent terminal status
        NotificationIntent intent = intentRepo.findByIntentId(delivery.getIntentId()).orElse(null);
        if (intent != null) {
            List<NotificationDelivery> all = deliveryRepo.findByIntentId(intent.getIntentId());
            NotificationIntent.Status terminal = statusResolver.resolve(all);
            if (terminal != null && intent.getStatus() != terminal) {
                intent.setStatus(terminal);
                intent.setTerminatedAt(now);
                intent.setProcessingLeaseUntil(null);
                intent.setProcessingOwner(null);
                intentRepo.save(intent);
                metrics.intentTerminated(terminal.name());
            }
        }

        // 4) Audit
        Map<String, Object> details = new HashMap<>();
        details.put("dlq_reason", reason);
        details.put("attempt_count", delivery.getAttemptCount());
        details.put("delivery_id", delivery.getId());
        if (delivery.getFailureReason() != null) {
            details.put("last_failure_reason", delivery.getFailureReason());
        }
        if (intent != null) {
            audit.publish("DLQ_TERMINATED", intent, delivery.getRecipientHash(),
                delivery.getChannel(), details);
        }

        metrics.dlqTerminated(reason);
        log.info("DLQ moved: intentId={} deliveryId={} channel={} reason={} attempts={}",
            delivery.getIntentId(), delivery.getId(), delivery.getChannel(),
            reason, delivery.getAttemptCount());
    }
}
