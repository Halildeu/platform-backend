package com.example.endpointadmin.service;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnPrimaryEndpointPlane
@ConditionalOnProperty(
        value = "endpoint-admin.domain-ops.reconciler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DomainOpsDispatchRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(DomainOpsDispatchRecoveryJob.class);

    private final DomainOpsBrokerService brokerService;
    private final int batchSize;

    public DomainOpsDispatchRecoveryJob(
            DomainOpsBrokerService brokerService,
            @Value("${endpoint-admin.domain-ops.reconciler.batch-size:100}") int batchSize) {
        this.brokerService = brokerService;
        if (batchSize <= 0 || batchSize > 500) {
            throw new IllegalArgumentException("domain-ops reconciler batch-size out of range [1, 500]: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${endpoint-admin.domain-ops.reconciler.interval-ms:60000}",
            initialDelayString = "${endpoint-admin.domain-ops.reconciler.initial-delay-ms:60000}")
    public void sweep() {
        try {
            int expired = brokerService.expireStaleDispatchWindows(batchSize);
            if (expired > 0) {
                log.info("domain-ops dispatch recovery expired stale requests count={}", expired);
            }
        } catch (RuntimeException ex) {
            log.warn("domain-ops dispatch recovery failed errorClass={}", ex.getClass().getSimpleName(), ex);
        }
    }
}
