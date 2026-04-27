package com.example.permission.dto.access;

import java.time.Instant;
import java.util.UUID;

/**
 * Faz 21.3 PR-D: list view DTO for {@code GET /api/v1/access/scope}. The
 * underlying entity is intentionally not exposed (avoids leaking JPA
 * internals like {@code notes} JSONB blob).
 */
public record ScopeListItem(
        Long id,
        UUID userId,
        Long orgId,
        String scopeKind,
        String scopeRef,
        Instant grantedAt,
        Boolean active
) {}
