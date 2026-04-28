package com.example.permission.dataaccess;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA "custom fragment" implementation. The class name MUST be
 * {@code DataAccessScopeOutboxRepositoryImpl} (repository interface name +
 * {@code Impl} suffix) so Spring Data auto-detects it and wires it behind
 * the {@link DataAccessScopeOutboxRepository} proxy. No
 * {@code @Repository} annotation is needed.
 *
 * <p>Faz 21.3 PR-G follow-up — Codex 019dd0e0 iter-1 BLOCKER 1 tech-debt
 * resolution. See {@link DataAccessScopeOutboxRepositoryCustom} class
 * Javadoc for the rationale.
 */
public class DataAccessScopeOutboxRepositoryImpl implements DataAccessScopeOutboxRepositoryCustom {

    /**
     * Reports-DB persistence context — the outbox lives in the secondary
     * {@code reports_db} datasource, same as the {@link DataAccessScope}
     * entity. The unit name matches the
     * {@code reportsDbEntityManagerFactory} bean configured in
     * {@code ReportsDbDataSourceConfig}.
     */
    @PersistenceContext(unitName = "reportsDb")
    private EntityManager entityManager;

    @Override
    @Transactional("reportsDbTransactionManager")
    @SuppressWarnings("unchecked")
    public List<DataAccessScopeOutboxEntry> claimBatch(String pollerId,
                                                       Instant lockedUntil,
                                                       int batchSize) {
        // Same SQL as the previous @Modifying List<Entity> definition; only
        // the JPA-layer wrapping changes. The entity-result form
        // (createNativeQuery + Entity.class second arg) is the framework
        // canonical idiom for native UPDATE … RETURNING * queries that
        // hydrate entity rows.
        String sql = """
                UPDATE data_access.scope_outbox
                SET status = 'PROCESSING',
                    locked_by = :pollerId,
                    locked_until = :lockedUntil,
                    attempt_count = attempt_count + 1
                WHERE id IN (
                    SELECT id FROM data_access.scope_outbox AS outer_row
                    WHERE outer_row.status = 'PENDING'
                      AND outer_row.next_attempt_at <= now()
                      AND NOT EXISTS (
                          SELECT 1 FROM data_access.scope_outbox AS older
                          WHERE older.tuple_user = outer_row.tuple_user
                            AND older.tuple_relation = outer_row.tuple_relation
                            AND older.tuple_object = outer_row.tuple_object
                            AND older.id < outer_row.id
                            AND older.status IN ('PENDING','PROCESSING')
                      )
                    ORDER BY outer_row.id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                RETURNING *
                """;

        return entityManager
                .createNativeQuery(sql, DataAccessScopeOutboxEntry.class)
                .setParameter("pollerId", pollerId)
                .setParameter("lockedUntil", lockedUntil)
                .setParameter("batchSize", batchSize)
                .getResultList();
    }
}
