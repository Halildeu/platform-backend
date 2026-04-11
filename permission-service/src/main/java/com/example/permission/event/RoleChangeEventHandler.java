package com.example.permission.event;

import com.example.permission.service.TupleSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Handles RoleChangeEvent AFTER the originating transaction commits.
 * This ensures OpenFGA tuple sync reads committed (not stale) data.
 *
 * CNS-20260411-002 #2-3: @TransactionalEventListener(AFTER_COMMIT)
 * + idempotent retry + error logging.
 */
@Component
public class RoleChangeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RoleChangeEventHandler.class);

    private final TupleSyncService tupleSyncService;

    public RoleChangeEventHandler(TupleSyncService tupleSyncService) {
        this.tupleSyncService = tupleSyncService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoleChange(RoleChangeEvent event) {
        Long roleId = event.roleId();
        log.info("Handling RoleChangeEvent for role {} (after-commit, async)", roleId);
        try {
            tupleSyncService.propagateRoleChange(roleId);
        } catch (Exception e) {
            log.error("Failed to propagate role {} change (after-commit handler). "
                    + "Tuple state may be stale until next role edit or manual sync.", roleId, e);
        }
    }
}
