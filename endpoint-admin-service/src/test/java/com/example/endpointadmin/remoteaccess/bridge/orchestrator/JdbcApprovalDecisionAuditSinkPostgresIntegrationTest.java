package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalDecisionAuditSink.ApprovalDecisionAuditRecord;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalGrantStore.ApprovalGrantKey;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteSessionApprovalRecorder.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10 post-pilot hardening #2 — {@link JdbcApprovalDecisionAuditSink} against a real PostgreSQL
 * (Testcontainers + Flyway V68): the append + read-back, the WORM trigger (UPDATE/DELETE/TRUNCATE refused), the
 * probe, AND the load-bearing atomicity — a durable-audit write failure inside the recorder's transaction rolls
 * the durable GRANT back, so a grant can never persist without its audit row.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JdbcApprovalDecisionAuditSinkPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String AUDIT = SCHEMA + ".remote_bridge_approval_decision_audit";
    private static final long NOW = 7_000_000L;
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "operator@acik";
    private static final String APPROVER = "approver@acik";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcApprovalDecisionAuditSink sink() {
        return new JdbcApprovalDecisionAuditSink(jdbc, SCHEMA);
    }

    private static ApprovalDecisionAuditRecord recorded(String sessionId) {
        return new ApprovalDecisionAuditRecord(sessionId, TENANT, SUBJECT, 1_000L, APPROVER, TENANT,
                Result.RECORDED, Set.of(RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY),
                Set.of(RemoteSessionCapability.VIEW_ONLY), NOW);
    }

    @Test
    void aDecisionIsAppendedDurably() {
        sink().record(recorded("s-rec"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM " + AUDIT + " WHERE session_id = ?", "s-rec");
        assertEquals(TENANT, row.get("operator_tenant_id"));
        assertEquals(SUBJECT, row.get("operator_subject"));
        assertEquals(APPROVER, row.get("approver_principal"));
        assertEquals("RECORDED", row.get("decision"));
        assertEquals("CONSTRAINED_PTY,VIEW_ONLY", row.get("requested_capabilities")); // sorted
        assertEquals("VIEW_ONLY", row.get("approved_capabilities"));
    }

    @Test
    void aDenialIsAppendedWithNoApprovedCapabilities() {
        sink().record(new ApprovalDecisionAuditRecord("s-deny", TENANT, SUBJECT, 1_000L, null, null,
                Result.DENIED_TENANT_MISMATCH, Set.of(RemoteSessionCapability.VIEW_ONLY), Set.of(), NOW));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM " + AUDIT + " WHERE session_id = ?", "s-deny");
        assertEquals("DENIED_TENANT_MISMATCH", row.get("decision"));
        assertTrue(row.get("approver_principal") == null, "a pre-flow denial has no approver");
        assertTrue(row.get("approved_capabilities") == null, "a denial approves nothing");
    }

    @Test
    void theTableIsWormUpdateAndDeleteRefused() {
        sink().record(recorded("s-worm"));
        assertThrows(RuntimeException.class,
                () -> jdbc.update("UPDATE " + AUDIT + " SET decision = 'TAMPERED' WHERE session_id = ?", "s-worm"),
                "UPDATE is refused by the WORM trigger");
        assertThrows(RuntimeException.class,
                () -> jdbc.update("DELETE FROM " + AUDIT + " WHERE session_id = ?", "s-worm"),
                "DELETE is refused by the WORM trigger");
        assertThrows(RuntimeException.class, () -> jdbc.execute("TRUNCATE " + AUDIT),
                "TRUNCATE is refused by the WORM trigger");
    }

    @Test
    void probeAvailableSucceedsWhenTheTableExists() {
        sink().probeAvailable();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // suspend the test tx so the TransactionTemplate is a REAL tx
    void aDurableAuditFailureRollsBackTheGrant() {
        JdbcApprovalGrantStore grantStore = new JdbcApprovalGrantStore(jdbc, SCHEMA);
        JdbcApprovalDecisionAuditSink auditSink = sink();
        ApprovalGrantKey key = new ApprovalGrantKey("s-atomic", TENANT, SUBJECT, 1_000L);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // the recorder writes the grant THEN the audit in ONE transaction. Force the audit to fail (an empty
        // requested-capabilities row violates the DB CHECK) — the whole transaction must roll back.
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            grantStore.record(key, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW + 60_000L);
            auditSink.record(new ApprovalDecisionAuditRecord("s-atomic", TENANT, SUBJECT, 1_000L, APPROVER, TENANT,
                    Result.RECORDED, Set.of() /* empty → requested_capabilities CHECK violation → throw */,
                    Set.of(RemoteSessionCapability.VIEW_ONLY), NOW));
        }));

        // the grant write was rolled back with the failed audit — no grant exists without its durable audit row
        assertTrue(grantStore.granted(key, NOW).isEmpty(),
                "a durable-audit write failure rolls back the grant (grant + audit are atomic)");
        List<Map<String, Object>> auditRows = jdbc.queryForList(
                "SELECT id FROM " + AUDIT + " WHERE session_id = ?", "s-atomic");
        assertTrue(auditRows.isEmpty(), "no partial audit row remains either");
    }
}
