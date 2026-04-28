package com.example.permission.dataaccess;

import com.example.permission.dataaccess.DataAccessScopeOutboxEntry.Action;
import com.example.permission.dataaccess.DataAccessScopeOutboxEntry.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Faz 21.3 PR-G — orchestrates {@code data_access.scope} grants and revokes.
 *
 * <p><strong>Outbox pattern (PR-G refactor of PR-D's direct write):</strong>
 * the request TX no longer makes the OpenFGA call. Instead it commits
 * <em>two</em> rows atomically: one in {@code data_access.scope} (the
 * authoritative business state) and one in {@code data_access.scope_outbox}
 * (the durable intent that the {@link OutboxPoller} will pick up to do the
 * actual FGA write asynchronously).
 *
 * <p>Why: the previous direct-call path (PR-D) had a residual edge ADR-0008
 * § "Tuple writer flow" called out — the FGA write succeeds, then the TX
 * commit fails afterwards (e.g. JDBC connection drop) → orphan FGA tuple.
 * The outbox shape eliminates that: PG row + outbox row commit together,
 * and the FGA write is retried until {@link Status#PROCESSED} or terminal
 * {@link Status#FAILED} after {@code maxAttempts} attempts.
 *
 * <p>Activation gate: multi-name {@code @ConditionalOnProperty}
 * (reports-db.enabled + erp.openfga.enabled). Codex 019dcee1 iter-2
 * BLOCKER established this contract; PR-G keeps it intact — the poller is
 * the new dependent, but its activation gate matches the service's exactly,
 * so they appear/disappear together.
 *
 * <p>Note: the {@link DataAccessScopeTupleWriter} bean is no longer
 * injected here. It survives in the codebase because the poller imports
 * the encoder side of it implicitly through
 * {@link DataAccessScopeTupleEncoder#encode(DataAccessScope)}; the
 * service-layer use site is gone, and the writer's own gates are
 * unchanged.
 */
@Service
@ConditionalOnProperty(
        name = {
                "spring.datasource.reports-db.enabled",
                "erp.openfga.enabled"
        },
        havingValue = "true"
)
@Transactional("reportsDbTransactionManager")
public class AccessScopeService {

    private static final Logger log = LoggerFactory.getLogger(AccessScopeService.class);

    private final DataAccessScopeRepository repository;
    private final DataAccessScopeOutboxRepository outboxRepository;

    public AccessScopeService(DataAccessScopeRepository repository,
                              DataAccessScopeOutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Result tuple — the controller exposes the outbox state in the 201
     * response so callers can correlate the PG row with the
     * (eventually-consistent) FGA tuple.
     */
    public record ScopeMutationResult(
            DataAccessScope scope,
            DataAccessScopeOutboxEntry outboxEntry
    ) {}

    public ScopeMutationResult grant(UUID userId,
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
            // saveAndFlush is required so the V19/V21 BEFORE-INSERT trigger
            // fires inside this try-block (default save() defers SQL to
            // commit time, where the exception would escape this handler).
            repository.saveAndFlush(scope);
        } catch (DataIntegrityViolationException ex) {
            DbErrorContext db = extractDbContext(ex);
            if ("23505".equals(db.sqlState())
                    || "uq_scope_active_assignment".equals(db.constraintName())) {
                throw new AccessScopeException.ScopeAlreadyGrantedException(
                        "Active scope already exists for (user=" + userId
                                + ", org=" + orgId + ", kind=" + scopeKind
                                + ", ref=" + scopeRef + ")",
                        ex);
            }
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

        DataAccessScopeOutboxEntry outbox = enqueueOutbox(scope, Action.GRANT);
        log.info("data_access scope granted (outbox id={}): scope_id={} user={} org={} kind={} ref={}",
                outbox.getId(), scope.getId(), userId, orgId, scopeKind, scopeRef);
        return new ScopeMutationResult(scope, outbox);
    }

    public ScopeMutationResult revoke(Long scopeId, UUID revokedBy) {
        DataAccessScope scope = repository.findById(scopeId)
                .orElseThrow(() -> new AccessScopeException.ScopeNotFoundException(scopeId));
        if (scope.getRevokedAt() != null) {
            throw new AccessScopeException.ScopeAlreadyRevokedException(
                    scopeId, scope.getRevokedAt());
        }
        scope.setRevokedAt(Instant.now());
        scope.setRevokedBy(revokedBy);
        repository.save(scope);

        DataAccessScopeOutboxEntry outbox = enqueueOutbox(scope, Action.REVOKE);
        log.info("data_access scope revoked (outbox id={}): scope_id={} user={} org={} kind={} ref={}",
                outbox.getId(), scope.getId(), scope.getUserId(), scope.getOrgId(),
                scope.getScopeKind(), scope.getScopeRef());
        return new ScopeMutationResult(scope, outbox);
    }

    @Transactional(value = "reportsDbTransactionManager", readOnly = true)
    public List<DataAccessScope> listActiveScopes(UUID userId, Long orgId) {
        return repository.findByUserIdAndOrgIdAndRevokedAtIsNull(userId, orgId);
    }

    private DataAccessScopeOutboxEntry enqueueOutbox(DataAccessScope scope, Action action) {
        DataAccessScopeTupleEncoder.FgaTuple tuple = DataAccessScopeTupleEncoder.encode(scope);
        // V23 Codex 019dd0e0 BLOCKER 2: typed tuple identity columns —
        // tuple_user/relation/object NOT NULL. Format mirrors the JSONB
        // payload's nested map for backward-compat with V22 mirror callers
        // (the columns are derived from the same encoder output, so they
        // stay in lock-step with the JSON shape downstream consumers see).
        DataAccessScopeOutboxEntry entry = new DataAccessScopeOutboxEntry();
        entry.setScopeId(scope.getId());
        entry.setAction(action);
        entry.setStatus(Status.PENDING);
        entry.setNextAttemptAt(Instant.now());
        entry.setCreatedAt(Instant.now());
        entry.setTupleUser("user:" + tuple.userId());
        entry.setTupleRelation(tuple.relation());
        entry.setTupleObject(tuple.objectType() + ":" + tuple.objectId());
        entry.setPayload(buildPayload(scope, tuple));
        return outboxRepository.save(entry);
    }

    /**
     * Snapshot the FGA tuple coordinates at write time so the poller does
     * not have to re-derive them from a possibly-mutated scope row later.
     * Includes the V23 composite {@code object} field alongside the
     * objectType/objectId split so future tuple-key consumers can choose
     * either shape without a payload migration.
     */
    private static Map<String, Object> buildPayload(DataAccessScope scope,
                                                    DataAccessScopeTupleEncoder.FgaTuple tuple) {
        Map<String, Object> tupleMap = new LinkedHashMap<>();
        tupleMap.put("user", "user:" + tuple.userId());
        tupleMap.put("relation", tuple.relation());
        tupleMap.put("object", tuple.objectType() + ":" + tuple.objectId());
        tupleMap.put("objectType", tuple.objectType());
        tupleMap.put("objectId", tuple.objectId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scopeId", scope.getId());
        payload.put("userId", scope.getUserId() == null ? null : scope.getUserId().toString());
        payload.put("orgId", scope.getOrgId());
        payload.put("scopeKind", scope.getScopeKind() == null ? null : scope.getScopeKind().name());
        payload.put("scopeRef", scope.getScopeRef());
        payload.put("tuple", tupleMap);
        return payload;
    }

    private static String expectedSourceTable(DataAccessScope.ScopeKind kind) {
        // V25 (Codex 019dd34e hybrid contract): COMPANY anchor changed from
        // workcube_mikrolink.COMPANY (80,246-row directory; tenant-blind) to
        // workcube_mikrolink.OUR_COMPANY (Workcube tenant table, COMP_ID PK).
        // The V25 CHECK constraint scope_kind_source_table_consistent enforces
        // this pairing; sending the legacy 'COMPANY' string here causes the
        // constraint to reject the INSERT before the trigger validate_scope_ref
        // even runs. See ADR-0008 § "Object id encoding" + V25 migration
        // (sql/migration/V25__tenant_anchor_fix.sql).
        return switch (kind) {
            case COMPANY -> "OUR_COMPANY";
            case PROJECT -> "PRO_PROJECTS";
            case BRANCH -> "BRANCH";
            case DEPOT -> "DEPARTMENT";
        };
    }

    /** Codex 019dcee1 iter-1 MAJOR-2 — structural DB error extractor. */
    private record DbErrorContext(String sqlState, String constraintName, String message) {}

    private static DbErrorContext extractDbContext(DataIntegrityViolationException ex) {
        String sqlState = null;
        String constraintName = null;
        String message = ex.getMessage();
        Throwable cause = ex.getCause();
        int depth = 0;
        while (cause != null && depth < 10) {
            if (cause.getMessage() != null) message = cause.getMessage();
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hcve) {
                if (hcve.getConstraintName() != null) constraintName = hcve.getConstraintName();
                if (hcve.getSQLException() != null
                        && hcve.getSQLException().getSQLState() != null) {
                    sqlState = hcve.getSQLException().getSQLState();
                }
            }
            if (cause instanceof java.sql.SQLException sqle) {
                if (sqle.getSQLState() != null) sqlState = sqle.getSQLState();
            }
            cause = cause.getCause();
            depth++;
        }
        return new DbErrorContext(sqlState, constraintName, message);
    }
}
