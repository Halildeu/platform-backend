package com.example.endpointadmin.exception;

import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse;

import java.util.Objects;

/**
 * BE-021 — thrown by the dedicated install create path when the
 * preflight recompute returns BLOCK. Mapped to {@code 409 Conflict} by
 * {@code GlobalExceptionHandler} with the same {@link InstallPreflightResponse}
 * shape the GET endpoint returns (Codex 019e6dfb iter-3 P1-2 absorb).
 */
public class InstallBlockedException extends RuntimeException {

    private final InstallPreflightResponse preflight;

    public InstallBlockedException(InstallPreflightResponse preflight) {
        super("Install blocked: " + preflight.decision());
        this.preflight = Objects.requireNonNull(preflight, "preflight");
    }

    public InstallPreflightResponse preflight() {
        return preflight;
    }
}
