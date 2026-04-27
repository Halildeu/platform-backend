package com.example.permission.dataaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Activation gate identical to the writer: both are gated on
 * {@code spring.datasource.reports-db.enabled=true} (env
 * {@code REPORTS_DB_ENABLED}), so when the secondary DS is off the entire
 * service + controller graph is absent and the controller bean is also
 * skipped (see {@link com.example.permission.controller.AccessScopeController}'s
 * {@code @ConditionalOnBean}).
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.reports-db.enabled", havingValue = "true")
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
            String causeMsg = mostSpecificMessage(ex);
            if (causeMsg != null && causeMsg.contains("uq_scope_active_assignment")) {
                throw new AccessScopeException.ScopeAlreadyGrantedException(
                        "Active scope already exists for (user=" + userId
                                + ", org=" + orgId + ", kind=" + scopeKind
                                + ", ref=" + scopeRef + ")",
                        ex);
            }
            if (causeMsg != null
                    && (causeMsg.contains("validate_scope_ref")
                            || causeMsg.contains("invalid scope_ref")
                            || causeMsg.contains("scope_kind_source_table"))) {
                throw new AccessScopeException.ScopeValidationException(
                        "Scope reference rejected by data_access lineage guard: " + causeMsg,
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

    private static String mostSpecificMessage(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return ex.getMessage();
    }
}
