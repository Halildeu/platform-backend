package com.example.endpointadmin.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * AG-028 Phase 0 — elevated-approver requirement during an unprotect
 * transition ({@code uninstall_protected: true → false}) on a catalog
 * uninstall settings change-request.
 *
 * <p>Thrown by
 * {@link CatalogUninstallSettingsChangeRequestService#requireElevatedIfUnprotect}.
 * Codex iter-2 P0 #4 absorb: protected denylist is a hard guard and
 * unprotecting a previously-protected row must not be a routine PATCH.
 * Until the platform exposes a super-admin role surface this guard rejects
 * with 403 FORBIDDEN.
 *
 * <p>BE-014A {@code noRollbackFor} pattern: the service annotation excludes
 * this exception from rollback so the durable
 * {@code ENDPOINT_CATALOG_UNINSTALL_SETTINGS_APPROVAL_REJECTED_ELEVATED_REQUIRED}
 * audit row written before the throw survives the rejected transaction.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class CatalogUninstallSettingsElevatedApproverRequiredException
        extends RuntimeException {

    private final UUID requestId;
    private final String approverSubject;

    public CatalogUninstallSettingsElevatedApproverRequiredException(
            UUID requestId, String approverSubject) {
        super("Unprotecting an uninstall-protected catalog row requires "
                + "elevated approver role (requestId=" + requestId + ").");
        this.requestId = requestId;
        this.approverSubject = approverSubject;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getApproverSubject() {
        return approverSubject;
    }
}
