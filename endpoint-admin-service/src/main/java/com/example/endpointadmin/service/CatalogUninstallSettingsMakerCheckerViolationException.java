package com.example.endpointadmin.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * AG-028 Phase 0 — maker-checker violation during catalog uninstall
 * settings change-request approval.
 *
 * <p>Thrown by
 * {@link CatalogUninstallSettingsChangeRequestService#approve} when the
 * approver subject equals the proposer subject (V31 DB CHECK
 * {@code ck_catalog_unins_change_maker_checker} also rejects it). Annotated
 * for 403 FORBIDDEN HTTP response.
 *
 * <p>BE-014A {@code noRollbackFor} pattern: the service annotation excludes
 * this exception from rollback so the durable
 * {@code ENDPOINT_CATALOG_UNINSTALL_SETTINGS_APPROVAL_REJECTED_MAKER_CHECKER}
 * audit row written before the throw survives the rejected transaction.
 *
 * <p>This is a checked-status runtime exception with a stable HTTP status
 * mapping via {@link ResponseStatus}.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class CatalogUninstallSettingsMakerCheckerViolationException
        extends RuntimeException {

    private final UUID requestId;
    private final String proposedBy;
    private final String approverSubject;

    public CatalogUninstallSettingsMakerCheckerViolationException(
            UUID requestId, String proposedBy, String approverSubject) {
        super("Maker-checker violation: approver must differ from proposer "
                + "(requestId=" + requestId + ").");
        this.requestId = requestId;
        this.proposedBy = proposedBy;
        this.approverSubject = approverSubject;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getProposedBy() {
        return proposedBy;
    }

    public String getApproverSubject() {
        return approverSubject;
    }
}
