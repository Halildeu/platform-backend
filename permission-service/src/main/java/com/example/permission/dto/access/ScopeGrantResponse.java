package com.example.permission.dto.access;

import java.time.Instant;
import java.util.UUID;

/**
 * Faz 21.3 PR-D: 201 response for a successful scope grant. The
 * {@code openFgaObjectType} / {@code openFgaObjectId} pair is what the writer
 * actually wrote to OpenFGA — exposed here so the caller can correlate the
 * PG row with the FGA tuple without having to re-derive the encoding.
 */
public record ScopeGrantResponse(
        Long scopeId,
        UUID userId,
        Long orgId,
        String scopeKind,
        String scopeRef,
        Instant grantedAt,
        String openFgaObjectType,
        String openFgaObjectId
) {}
