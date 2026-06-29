package com.example.endpointadmin.service.rolloutfailure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Advisory bridge from a committed structured security/network block to FDQ
 * EDR_NETWORK ingest. Runs after commit and swallows failures so inventory result
 * submission remains authoritative and durable even if FDQ advisory ingest fails.
 */
@Component
public class RolloutFailureSecurityNetworkIngestListener {

    private static final Logger log = LoggerFactory.getLogger(RolloutFailureSecurityNetworkIngestListener.class);

    private final RolloutFailureSecurityNetworkIngestService service;

    public RolloutFailureSecurityNetworkIngestListener(RolloutFailureSecurityNetworkIngestService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSecurityNetworkBlockSubmitted(SecurityNetworkBlockSubmittedEvent event) {
        try {
            int ingested = service.ingest(event);
            if (ingested > 0) {
                log.debug("securityNetwork FDQ ingest created/coalesced {} EDR_NETWORK item(s) for result {}",
                        ingested, event.commandResultId());
            }
        } catch (RuntimeException ex) {
            log.warn("securityNetwork FDQ ingest failed for command result {}; skipping",
                    event.commandResultId(), ex);
        }
    }
}
