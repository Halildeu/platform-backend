package com.example.permission.dto.access;

import com.example.permission.dataaccess.DataAccessScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Faz 21.3 PR-D: payload for {@code POST /api/v1/access/scope}. The
 * {@code scope_source_table} field is intentionally absent — the service
 * derives it from {@code scopeKind} so the V19 CHECK constraint cannot be
 * tripped by client-supplied inconsistencies.
 */
public record ScopeGrantRequest(
        @NotNull UUID userId,
        @NotNull @Positive Long orgId,
        @NotNull DataAccessScope.ScopeKind scopeKind,
        @NotBlank String scopeRef,
        UUID grantedBy
) {}
