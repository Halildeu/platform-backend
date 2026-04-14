package com.example.permission.outbox;

import com.example.permission.service.TupleSyncService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Faz 4-a: Polls the outbox table for PENDING entries and retries tuple sync.
 * Runs every 30 seconds. Max 5 attempts per entry (then FAILED → dead letter).
 *
 * Enabled only when erp.openfga.enabled=true (no polling in dev mode).
 */
@Component
@ConditionalOnProperty(name = "erp.openfga.enabled", havingValue = "true")
public class TupleSyncOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(TupleSyncOutboxPoller.class);

    private final TupleSyncOutboxRepository outboxRepository;
    private final TupleSyncService tupleSyncService;
    private final Counter failedCounter;

    public TupleSyncOutboxPoller(TupleSyncOutboxRepository outboxRepository,
                                  TupleSyncService tupleSyncService,
                                  org.springframework.beans.factory.ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.outboxRepository = outboxRepository;
        this.tupleSyncService = tupleSyncService;
        // B4 (Rev 19): Outbox failed entry counter (null-safe when MeterRegistry absent, e.g. test)
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        this.failedCounter = meterRegistry != null
                ? Counter.builder("tuple_sync_outbox_failed_total")
                        .description("Total outbox entries that exhausted max retries")
                        .register(meterRegistry)
                : null;
    }

    private static final int BATCH_SIZE = 50;

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:30000}")
    @Transactional
    public void pollAndProcess() {
        List<TupleSyncOutboxEntry> pending = outboxRepository.findPendingForUpdate(BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }

        log.info("Outbox poller: {} pending entries", pending.size());

        for (TupleSyncOutboxEntry entry : pending) {
            entry.markProcessing();
            outboxRepository.save(entry);

            try {
                tupleSyncService.propagateRoleChange(entry.getRoleId());
                entry.markDone();
                log.info("Outbox entry {} (role {}) synced successfully (attempt {})",
                        entry.getId(), entry.getRoleId(), entry.getAttempts());
            } catch (Exception e) {
                entry.markFailed(e.getMessage());
                // B4 (Rev 19): Increment counter when entry exhausts retries
                if (entry.getAttempts() >= entry.getMaxAttempts() && failedCounter != null) {
                    failedCounter.increment();
                }
                log.warn("Outbox entry {} (role {}) failed attempt {}/{}: {}",
                        entry.getId(), entry.getRoleId(), entry.getAttempts(),
                        entry.getMaxAttempts(), e.getMessage());
            }

            outboxRepository.save(entry);
        }
    }
}
