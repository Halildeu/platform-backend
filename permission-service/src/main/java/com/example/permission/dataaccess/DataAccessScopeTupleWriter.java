package com.example.permission.dataaccess;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Faz 21.3 PR-C: bridge service that propagates a {@link DataAccessScope} row
 * from {@code data_access.scope} to OpenFGA as a {@code viewer} tuple per
 * ADR-0008 explicit-scope contract.
 *
 * <p>Idempotency contract: delegated to
 * {@link OpenFgaAuthzService#writeTuple} / {@link OpenFgaAuthzService#deleteTuple},
 * both of which already swallow OpenFGA's "tuple already exists" /
 * "tuple does not exist" validation errors as idempotent no-ops (OI-03).
 * No additional retry/dedupe logic is needed here.
 *
 * <p>Conditional activation:
 * <ul>
 *   <li>{@code spring.datasource.reports-db.url} must be set (without it the
 *       {@link DataAccessScopeRepository} bean does not exist anyway).</li>
 *   <li>{@link OpenFgaAuthzService} bean must be present (only registered when
 *       {@code erp.openfga.enabled=true} per
 *       {@link com.example.permission.config.OpenFgaAuthzConfig}).</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.reports-db.url")
@ConditionalOnBean(OpenFgaAuthzService.class)
public class DataAccessScopeTupleWriter {

    private static final Logger log = LoggerFactory.getLogger(DataAccessScopeTupleWriter.class);

    private final OpenFgaAuthzService authzService;

    public DataAccessScopeTupleWriter(OpenFgaAuthzService authzService) {
        this.authzService = authzService;
    }

    public void writeScopeTuple(DataAccessScope scope) {
        var tuple = DataAccessScopeTupleEncoder.encode(scope);
        authzService.writeTuple(tuple.userId(), tuple.relation(), tuple.objectType(), tuple.objectId());
        log.info("data_access scope tuple written: scope_id={} kind={} → {}:{} {} user:{}",
                scope.getId(), scope.getScopeKind(),
                tuple.objectType(), tuple.objectId(), tuple.relation(), tuple.userId());
    }

    public void deleteScopeTuple(DataAccessScope scope) {
        var tuple = DataAccessScopeTupleEncoder.encode(scope);
        authzService.deleteTuple(tuple.userId(), tuple.relation(), tuple.objectType(), tuple.objectId());
        log.info("data_access scope tuple deleted: scope_id={} kind={} → {}:{} {} user:{}",
                scope.getId(), scope.getScopeKind(),
                tuple.objectType(), tuple.objectId(), tuple.relation(), tuple.userId());
    }
}
