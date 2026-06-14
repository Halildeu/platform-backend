package com.example.endpointadmin.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Faz 22.8A.3b (#648) — maker-checker violation: the approver is the same
 * subject who proposed the backup dry-run. 403. Thrown AFTER a durable audit
 * row is written (the approve transaction uses {@code noRollbackFor} on this
 * type, mirroring the uninstall BE-014A pattern).
 */
public class EndpointBackupDryrunMakerCheckerViolationException extends ResponseStatusException {

    public EndpointBackupDryrunMakerCheckerViolationException(UUID requestId, String proposer, String approver) {
        super(HttpStatus.FORBIDDEN,
                "Maker-checker violation: the approver must differ from the proposer for backup dry-run request "
                        + requestId + ".");
    }
}
