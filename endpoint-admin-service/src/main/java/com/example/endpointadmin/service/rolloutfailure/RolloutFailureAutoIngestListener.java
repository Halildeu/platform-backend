package com.example.endpointadmin.service.rolloutfailure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges a committed failed command result to the rollout failed-device-queue
 * auto-ingest (Faz 22.5 #527 §9.2 slice-2a). Mirrors the BE-023 compliance
 * listener: runs AFTER_COMMIT (so the result row is durably visible) and
 * SWALLOWS every exception — auto-ingest is advisory and must NEVER affect the
 * already-committed submitResult transaction. The service it calls opens its own
 * REQUIRES_NEW transaction.
 */
@Component
public class RolloutFailureAutoIngestListener {

    private static final Logger log = LoggerFactory.getLogger(RolloutFailureAutoIngestListener.class);

    private final RolloutFailureAutoIngestService service;

    public RolloutFailureAutoIngestListener(RolloutFailureAutoIngestService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandResultFailed(CommandResultFailedEvent event) {
        try {
            boolean ingested = service.ingest(event);
            if (ingested) {
                log.debug("§9.2 auto-ingest seeded/coalesced a rollout failure for device {} (result {})",
                        event.deviceId(), event.commandResultId());
            }
        } catch (RuntimeException ex) {
            // Advisory — never break the committed submitResult.
            log.warn("§9.2 auto-ingest failed for command result {} (device {}); skipping",
                    event.commandResultId(), event.deviceId(), ex);
        }
    }
}
