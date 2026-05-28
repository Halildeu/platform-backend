package com.example.endpointadmin.service.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * BE-021 — Listens for {@link EndpointInstallAuditRecordedEvent} and
 * triggers a background compliance re-evaluation after the parent
 * install-result transaction commits. Same failure semantics as
 * {@link ComplianceInventoryEventListener}: advisory-lock contention
 * silently skips (next event will fire another), device-deletion maps
 * to debug, and other errors are logged at WARN so the parent
 * transaction is unaffected.
 */
@Component
public class ComplianceInstallAuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(ComplianceInstallAuditEventListener.class);

    private final EndpointComplianceService complianceService;

    public ComplianceInstallAuditEventListener(EndpointComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInstallAuditRecorded(EndpointInstallAuditRecordedEvent event) {
        try {
            complianceService.evaluateForEvent(event.tenantId(), event.deviceId())
                    .ifPresent(outcome -> log.debug(
                            "BE-021 install-audit-driven compliance re-eval succeeded "
                                    + "tenant={} device={} auditId={} evaluationId={} decision={}",
                            event.tenantId(), event.deviceId(), event.installAuditId(),
                            outcome.evaluationId(), outcome.decision()));
        } catch (RuntimeException ex) {
            log.warn(
                    "BE-021 install-audit-driven compliance re-eval failed "
                            + "tenant={} device={} auditId={}: {}",
                    event.tenantId(), event.deviceId(), event.installAuditId(),
                    ex.getMessage(), ex);
        }
    }
}
