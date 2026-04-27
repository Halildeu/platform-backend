package com.example.permission.dataaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Faz 21.3 PR-D: orchestrates {@code data_access.scope} grants and revokes
 * across PG (V19/V20 schema) and OpenFGA. The service is the only legitimate
 * caller of {@link DataAccessScopeTupleWriter} — controllers go through here.
 *
 * <p>Transaction boundary: class-level
 * {@code @Transactional("reportsDbTransactionManager")} so the PG INSERT (or
 * UPDATE for revoke) and the OpenFGA tuple write happen under the same TX.
 * If the tuple write throws, the PG transaction rolls back and the row is
 * never visible. The reverse (PG fails after FGA write) cannot happen here:
 * we {@code saveAndFlush} first, then call the writer; if {@code saveAndFlush}
 * throws, the writer is never invoked.
 *
 * <p>Edge case acknowledged in ADR-0008 § Tuple writer flow: the FGA write
 * succeeds but the TX commit fails afterwards (e.g. JDBC connection drop).
 * That produces an orphan FGA tuple. The outbox pattern (V21) eliminates it;
 * out of scope for this PR.
 *
 * <p>Activation gate: this service requires both the secondary
 * {@code reports_db} datasource ({@code spring.datasource.reports-db.enabled=true}
 * / env {@code REPORTS_DB_ENABLED}) AND a live {@link DataAccessScopeTupleWriter}
 * bean (which itself requires {@code erp.openfga.enabled=true} per
 * {@link com.example.permission.config.OpenFgaAuthzConfig}). Without both,
 * the service bean is absent and
 * {@link com.example.permission.controller.AccessScopeController} receives
 * {@code Optional.empty()} and short-circuits to
 * {@link org.springframework.http.HttpStatus#SERVICE_UNAVAILABLE 503}. The
 * explicit {@code @ConditionalOnBean(DataAccessScopeTupleWriter.class)}
 * prevents a boot-time NoSuchBeanDefinition failure in the
 * {@code REPORTS_DB_ENABLED=true + erp.openfga.enabled=false} configuration
 * (Codex 019dcee1 iter-1 MAJOR-1) — the application now boots and degrades
 * to 503 instead of failing to start.
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.reports-db.enabled", havingValue = "true")
@ConditionalOnBean(DataAccessScopeTupleWriter.class)
@Transactional("reportsDbTransactionManager")
public class AccessScopeService {

    private static final Logger log = LoggerFactory.getLogger(AccessScopeService.class);

    private final DataAccessScopeRepository repository;
    private final DataAccessScopeTupleWriter tupleWriter;

    public AccessScopeService(DataAccessScopeRepository repository,
                              DataAccessScopeTupleWriter tupleWriter) {
        this.repository = repository;
        this.tupleWriter = tupleWriter;
    }

    public DataAccessScope grant(UUID userId,
                                 Long orgId,
                                 DataAccessScope.ScopeKind scopeKind,
                                 String scopeRef,
                                 UUID grantedBy) {
        DataAccessScope scope = new DataAccessScope();
        scope.setUserId(userId);
        scope.setOrgId(orgId);
        scope.setScopeKind(scopeKind);
        scope.setScopeSourceSchema("workcube_mikrolink");
        scope.setScopeSourceTable(expectedSourceTable(scopeKind));
        scope.setScopeRef(scopeRef);
        scope.setGrantedAt(Instant.now());
        scope.setGrantedBy(grantedBy);

        try {
            // saveAndFlush is required so the V19 BEFORE-INSERT trigger fires
            // inside this try-block (default save() defers the SQL to commit
            // time, where the exception would escape this handler).
            repository.saveAndFlush(scope);
        } catch (DataIntegrityViolationException ex) {
            DbErrorContext db = extractDbContext(ex);

            // PG SQLState 23505 = unique_violation. Either the SQLState or
            // the constraint name is enough to route — both come straight
            // from PG and survive locale changes that would have broken the
            // previous substring-match path.
            if ("23505".equals(db.sqlState())
                    || "uq_scope_active_assignment".equals(db.constraintName())) {
                throw new AccessScopeException.ScopeAlreadyGrantedException(
                        "Active scope already exists for (user=" + userId
                                + ", org=" + orgId + ", kind=" + scopeKind
                                + ", ref=" + scopeRef + ")",
                        ex);
            }
            // PG SQLState P0001 = trigger RAISE EXCEPTION (V19
            // validate_scope_ref). PG SQLState 23514 = check_violation
            // (scope_kind_source_table_consistent). The message-fallback is
            // a safety net for triggers that omit a constraint name in their
            // RAISE EXCEPTION; it can be removed once the V19 trigger is
            // tightened to specify USING ERRCODE/CONSTRAINT in a follow-up
            // gitops PR.
            if ("P0001".equals(db.sqlState())
                    || "23514".equals(db.sqlState())
                    || "scope_kind_source_table_consistent".equals(db.constraintName())
                    || (db.message() != null && db.message().contains("validate_scope_ref"))) {
                throw new AccessScopeException.ScopeValidationException(
                        "Scope reference rejected by data_access lineage guard: " + db.message(),
                        ex);
            }
            throw ex;
        }

        tupleWriter.writeScopeTuple(scope);
        log.info("data_access scope granted: id={} user={} org={} kind={} ref={}",
                scope.getId(), userId, orgId, scopeKind, scopeRef);
        return scope;
    }

    public DataAccessScope revoke(Long scopeId, UUID revokedBy) {
        DataAccessScope scope = repository.findById(scopeId)
                .orElseThrow(() -> new AccessScopeException.ScopeNotFoundException(scopeId));
        if (scope.getRevokedAt() != null) {
            throw new AccessScopeException.ScopeAlreadyRevokedException(
                    scopeId, scope.getRevokedAt());
        }
        scope.setRevokedAt(Instant.now());
        scope.setRevokedBy(revokedBy);
        repository.save(scope);
        tupleWriter.deleteScopeTuple(scope);
        log.info("data_access scope revoked: id={} user={} org={} kind={} ref={}",
                scope.getId(), scope.getUserId(), scope.getOrgId(),
                scope.getScopeKind(), scope.getScopeRef());
        return scope;
    }

    @Transactional(value = "reportsDbTransactionManager", readOnly = true)
    public List<DataAccessScope> listActiveScopes(UUID userId, Long orgId) {
        return repository.findByUserIdAndOrgIdAndRevokedAtIsNull(userId, orgId);
    }

    /**
     * Mirrors the V19/V20 {@code scope_kind_source_table_consistent} CHECK
     * constraint. Centralising the mapping here means the controller never
     * has to pass {@code source_table} explicitly — eliminating a class of
     * client mistakes — and the encoder's {@code depot → warehouse} contract
     * stays internally consistent (see ADR-0008 § Naming).
     */
    private static String expectedSourceTable(DataAccessScope.ScopeKind kind) {
        return switch (kind) {
            case COMPANY -> "COMPANY";
            case PROJECT -> "PRO_PROJECTS";
            case BRANCH -> "BRANCH";
            case DEPOT -> "DEPARTMENT";
        };
    }

    /**
     * Faz 21.3 PR-D iter-1 MAJOR-2 hardening (Codex 019dcee1): structural
     * extraction of the DB error context (SQLState + constraint name) instead
     * of substring matching on the message. PG locale changes, message
     * reformatting in a future driver upgrade, or constraint renames in a
     * later migration would all silently break the previous string-match
     * path and surface as generic 500s. SQLState codes ({@code 23505},
     * {@code 23514}, {@code P0001}) are stable across locales, and the
     * constraint name comes from Hibernate's
     * {@link org.hibernate.exception.ConstraintViolationException} (which
     * parses it out of the PG error response). The PostgreSQL driver itself
     * is on {@code <scope>runtime</scope>} in {@code pom.xml}, so we route
     * through the JDBC {@link java.sql.SQLException} to read SQLState rather
     * than reaching for {@code org.postgresql.util.PSQLException} directly.
     * Falls back to the deepest non-null message as a safety net for
     * triggers that do not specify {@code USING ERRCODE/CONSTRAINT}.
     */
    private record DbErrorContext(String sqlState, String constraintName, String message) {}

    private static DbErrorContext extractDbContext(DataIntegrityViolationException ex) {
        String sqlState = null;
        String constraintName = null;
        String message = ex.getMessage();

        Throwable cause = ex.getCause();
        int depth = 0;
        while (cause != null && depth < 10) {
            if (cause.getMessage() != null) {
                message = cause.getMessage();
            }
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hcve) {
                if (hcve.getConstraintName() != null) {
                    constraintName = hcve.getConstraintName();
                }
                if (hcve.getSQLException() != null
                        && hcve.getSQLException().getSQLState() != null) {
                    sqlState = hcve.getSQLException().getSQLState();
                }
            }
            // Catch the deepest JDBC SQLException's SQLState even if it is
            // not wrapped by a ConstraintViolationException (e.g. raw JDBC
            // errors from native queries). Driver-specific subclasses are
            // intentionally not referenced — postgres' jdbc driver is on
            // runtime scope in pom.xml.
            if (cause instanceof java.sql.SQLException sqle) {
                if (sqle.getSQLState() != null) {
                    sqlState = sqle.getSQLState();
                }
            }
            cause = cause.getCause();
            depth++;
        }
        return new DbErrorContext(sqlState, constraintName, message);
    }
}
