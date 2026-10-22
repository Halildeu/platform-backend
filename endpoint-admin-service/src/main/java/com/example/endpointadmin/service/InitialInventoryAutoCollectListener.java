package com.example.endpointadmin.service;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges a committed heartbeat to {@link InitialInventoryAutoCollectService}
 * (platform-backend#1206). Mirrors {@code RolloutFailureAutoIngestListener}:
 * runs AFTER_COMMIT, so the heartbeat is durable whatever happens here, and
 * swallows every exception. Queuing the first inventory is best-effort and must
 * never turn an accepted heartbeat into an error for the agent; the next
 * heartbeat simply tries again.
 */
@Component
@ConditionalOnPrimaryEndpointPlane
public class InitialInventoryAutoCollectListener {

    private static final Logger log = LoggerFactory.getLogger(InitialInventoryAutoCollectListener.class);

    private final InitialInventoryAutoCollectService service;

    public InitialInventoryAutoCollectListener(InitialInventoryAutoCollectService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHeartbeatRecorded(DeviceHeartbeatRecordedEvent event) {
        if (!event.collectInventoryCapable()) {
            return;
        }
        try {
            service.enqueueIfNeeded(event.tenantId(), event.deviceId())
                    .ifPresent(commandId -> log.info(
                            "Queued first inventory collection {} for device {} (no snapshot yet)",
                            commandId, event.deviceId()));
        } catch (RuntimeException ex) {
            log.warn("Could not queue first inventory collection for device {}; next heartbeat retries",
                    event.deviceId(), ex);
        }
    }
}
