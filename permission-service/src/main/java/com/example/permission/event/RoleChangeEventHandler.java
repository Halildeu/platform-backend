package com.example.permission.event;

import com.example.permission.outbox.TupleSyncOutboxEntry;
import com.example.permission.outbox.TupleSyncOutboxRepository;
import com.example.permission.service.TupleSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Handles RoleChangeEvent AFTER the originating transaction commits.
 *
 * Faz 4-a durable pattern:
 * 1. BEFORE_COMMIT: Write outbox entry (durable record in same transaction)
 * 2. AFTER_COMMIT: Attempt immediate sync (fast path, async)
 *    - Success → mark outbox DONE
 *    - Failure → outbox stays PENDING → TupleSyncOutboxPoller retries
 *
 * CNS-20260411-002 #2-3: @TransactionalEventListener(AFTER_COMMIT)
 * CNS-009: upgraded from best-effort to durable outbox pattern.
 */
@Component
public class RoleChangeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RoleChangeEventHandler.class);

    private final TupleSyncService tupleSyncService;
    private final TupleSyncOutboxRepository outboxRepository;

    public RoleChangeEventHandler(TupleSyncService tupleSyncService,
                                   TupleSyncOutboxRepository outboxRepository) {
        this.tupleSyncService = tupleSyncService;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Phase 1: Write outbox entry in the SAME transaction as the role change.
     * This guarantees the outbox record exists even if the app crashes after commit.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void writeOutbox(RoleChangeEvent event) {
        TupleSyncOutboxEntry entry = new TupleSyncOutboxEntry(event.roleId());
        outboxRepository.save(entry);
        log.debug("Outbox entry written for role {} (before-commit)", event.roleId());
    }

    /**
     * Phase 2: Attempt immediate sync after commit (fast path).
     * If this succeeds, outbox entry is marked DONE immediately.
     * If this fails, the poller will retry from the outbox table.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoleChange(RoleChangeEvent event) {
        Long roleId = event.roleId();
        log.info("Handling RoleChangeEvent for role {} (after-commit, async + outbox)", roleId);
        try {
            tupleSyncService.propagateRoleChange(roleId);
            // Mark outbox entry DONE (find latest pending for this role)
            outboxRepository.findPendingEntries().stream()
                    .filter(e -> e.getRoleId().equals(roleId))
                    .findFirst()
                    .ifPresent(e -> {
                        e.markDone();
                        outboxRepository.save(e);
                        log.debug("Outbox entry {} marked DONE (fast path)", e.getId());
                    });
        } catch (Exception e) {
            log.warn("Fast path failed for role {} — outbox poller will retry: {}",
                    roleId, e.getMessage());
        }
    }
}
