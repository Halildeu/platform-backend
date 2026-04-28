package com.example.permission.dataaccess;

import java.time.Instant;
import java.util.List;

/**
 * Faz 21.3 PR-G follow-up — Codex 019dd0e0 iter-1 BLOCKER 1 (re-classified
 * as MAJOR tech-debt at iter-2 once CI Linux integration test PASS proved
 * the prior shape worked) — custom repository fragment for
 * {@link DataAccessScopeOutboxRepository#claimBatch}.
 *
 * <p>The previous implementation used Spring Data JPA's
 * {@code @Modifying List<Entity>} return shape on a native
 * {@code UPDATE … RETURNING *} query. That works in the current
 * Spring Boot 3.x line but is non-idiomatic — {@code @Modifying} contracts
 * for {@code void}/{@code int} returns, and the entity-list fallback
 * relies on framework behavior that may shift in a future major upgrade.
 *
 * <p>This fragment moves the claim into a Spring Data JPA "custom
 * repository" implementation ({@link DataAccessScopeOutboxRepositoryImpl})
 * so the entity-list output is produced by an explicit
 * {@code EntityManager.createNativeQuery(sql, Entity.class)} call — the
 * canonical pattern for native queries that return managed entities. SQL
 * semantic and behaviour are identical; only the framework idiom changes.
 */
public interface DataAccessScopeOutboxRepositoryCustom {

    /**
     * Atomic claim of pending entries for processing. The outer UPDATE
     * uses an inner SELECT with {@code FOR UPDATE SKIP LOCKED} so multiple
     * poller instances can claim disjoint batches without blocking each
     * other. {@code RETURNING *} gives the updated rows back so the caller
     * does not need a separate SELECT round trip.
     *
     * <p>Per-tuple ordering guard (Codex 019dd0e0 iter-2 BLOCKER 2 fix —
     * Yol β typed columns): the {@code NOT EXISTS} subquery keys on
     * {@code (tuple_user, tuple_relation, tuple_object)} rather than
     * {@code scope_id} so revoke + re-grant cycles (which produce
     * different scope.id values targeting the same FGA tuple) are still
     * serialised. V22's {@code idx_scope_outbox_scope_ordering} was
     * dropped in V23 in favour of the correctness-correct
     * {@code idx_scope_outbox_tuple_ordering}.
     */
    List<DataAccessScopeOutboxEntry> claimBatch(
            String pollerId,
            Instant lockedUntil,
            int batchSize);
}
