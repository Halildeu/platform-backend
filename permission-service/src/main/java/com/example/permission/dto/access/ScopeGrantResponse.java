package com.example.permission.dto.access;

import java.time.Instant;
import java.util.UUID;

/**
 * Faz 21.3 PR-G — 201 response for a successful scope grant.
 *
 * <p>Three additive fields over the PR-D shape ({@code tupleSyncStatus},
 * {@code outboxId}, {@code processedAt}) expose the durable-outbox lifecycle
 * to the caller. {@code tupleSyncStatus} starts at {@code "PENDING"} and the
 * UI can poll the corresponding outbox row to learn when the OpenFGA tuple
 * was actually written ({@code "PROCESSED"}) or terminally failed
 * ({@code "FAILED"}).
 *
 * <p>The {@code openFgaObjectType}/{@code openFgaObjectId} pair is unchanged
 * from PR-D — it still describes what the writer <em>will</em> post to FGA;
 * the new {@code processedAt} field records when the writer actually
 * <em>did</em> post it.
 */
public record ScopeGrantResponse(
        Long scopeId,
        UUID userId,
        Long orgId,
        String scopeKind,
        String scopeRef,
        Instant grantedAt,
        String openFgaObjectType,
        String openFgaObjectId,
        String tupleSyncStatus,
        Long outboxId,
        Instant processedAt
) {}
