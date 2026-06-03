package com.example.endpointadmin.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * AG-028 Phase 0 — maker-checker violation during catalog uninstall
 * settings change-request approval.
 *
 * <p>Thrown by
 * {@link CatalogUninstallSettingsChangeRequestService#approve} when the
 * approver subject equals the proposer subject (V31 DB CHECK
 * {@code ck_catalog_unins_change_maker_checker} also rejects it).
 *
 * <p>Subclasses {@link ResponseStatusException} (not just a
 * {@code @ResponseStatus(FORBIDDEN)} marker) because the repo's
 * {@code GlobalExceptionHandler} has a catch-all
 * {@code @ExceptionHandler(Exception.class)} that would otherwise map this
 * to HTTP 500. The Spring MVC dispatcher resolves a
 * {@code ResponseStatusException} explicitly to its declared status code,
 * bypassing the catch-all. Mirrors the BE-020
 * {@link com.example.endpointadmin.exception.CatalogMakerCheckerViolationException}
 * pattern (Codex iter-2 absorb).
 *
 * <p>BE-014A {@code noRollbackFor} pattern: the service annotation excludes
 * this exception from rollback so the durable
 * {@code ENDPOINT_CATALOG_UNINSTALL_SETTINGS_APPROVAL_REJECTED_MAKER_CHECKER}
 * audit row written before the throw survives the rejected transaction.
 *
 * <p>Maps to HTTP 403 (Forbidden) — distinct from BE-020's 422 to keep
 * the AG-028 surface distinct from catalog-creation maker-checker.
 */
public class CatalogUninstallSettingsMakerCheckerViolationException
        extends ResponseStatusException {

    private final UUID requestId;
    private final String proposedBy;
    private final String approverSubject;

    public CatalogUninstallSettingsMakerCheckerViolationException(
            UUID requestId, String proposedBy, String approverSubject) {
        super(HttpStatus.FORBIDDEN,
                "Maker-checker violation: approver must differ from proposer "
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
