package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalGrantStore.ApprovalGrantKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10 post-pilot hardening — {@link JdbcApprovalGrantStore} against a real PostgreSQL (Testcontainers +
 * Flyway V67): record/granted round-trip + upsert + expiry + session-incarnation isolation + the fail-closed
 * paths (missing / expired / empty-grant / unknown-stored-capability). Proves the durable store has the SAME
 * fail-closed contract as the in-memory reference, backed by a table that survives restart.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JdbcApprovalGrantStorePostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final long NOW = 5_000_000L;
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "operator@acik";

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

    private JdbcApprovalGrantStore store() {
        return new JdbcApprovalGrantStore(jdbc, SCHEMA);
    }

    private static ApprovalGrantKey key(String sessionId, long sessionStart) {
        return new ApprovalGrantKey(sessionId, TENANT, SUBJECT, sessionStart);
    }

    @Test
    void aRecordedGrantIsReadBackWhileActive() {
        JdbcApprovalGrantStore store = store();
        store.record(key("s1", 1_000L),
                Set.of(RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY), NOW + 60_000L);

        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY),
                store.granted(key("s1", 1_000L), NOW));
    }

    @Test
    void anExpiredGrantIsFailClosed() {
        JdbcApprovalGrantStore store = store();
        store.record(key("s-exp", 1_000L), Set.of(RemoteSessionCapability.VIEW_ONLY), NOW);
        // at the expiry instant (now >= expiresAt) the grant is gone — matches the in-memory boundary
        assertTrue(store.granted(key("s-exp", 1_000L), NOW).isEmpty());
        assertTrue(store.granted(key("s-exp", 1_000L), NOW + 1).isEmpty());
    }

    @Test
    void aMissingKeyGrantsNothing() {
        assertTrue(store().granted(key("never-recorded", 1_000L), NOW).isEmpty());
    }

    @Test
    void aNullKeyGrantsNothing() {
        assertTrue(store().granted(null, NOW).isEmpty());
    }

    @Test
    void aReRecordReplacesTheGrant() {
        JdbcApprovalGrantStore store = store();
        store.record(key("s-upsert", 1_000L), Set.of(RemoteSessionCapability.VIEW_ONLY,
                RemoteSessionCapability.CONSTRAINED_PTY), NOW + 60_000L);
        // a re-recorded approval for the same incarnation REPLACES (mirrors the in-memory put) — not a union
        store.record(key("s-upsert", 1_000L), Set.of(RemoteSessionCapability.VIEW_ONLY), NOW + 60_000L);

        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY), store.granted(key("s-upsert", 1_000L), NOW));
    }

    @Test
    void aDifferentSessionIncarnationDoesNotInheritTheGrant() {
        JdbcApprovalGrantStore store = store();
        store.record(key("s-reuse", 1_000L), Set.of(RemoteSessionCapability.VIEW_ONLY), NOW + 60_000L);
        // same sessionId, a LATER incarnation (different sessionStart) — must NOT inherit the earlier grant
        assertTrue(store.granted(key("s-reuse", 2_000L), NOW).isEmpty());
        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY), store.granted(key("s-reuse", 1_000L), NOW));
    }

    @Test
    void anEmptyCapabilityGrantIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> store().record(key("s-empty", 1_000L), Set.of(), NOW + 60_000L));
    }

    @Test
    void anUnknownStoredCapabilityIsFailClosed() {
        // a corrupt/forward-incompatible row (an unknown capability token) must grant NOTHING — never a partial
        // set (all-or-nothing parse). Insert it directly to simulate the corruption.
        jdbc.update("INSERT INTO " + SCHEMA + ".remote_bridge_approval_grant (session_id, operator_tenant_id,"
                        + " operator_subject, session_start_epoch_millis, capabilities, expires_at_epoch_millis)"
                        + " VALUES (?,?,?,?,?,?)",
                "s-bad", TENANT, SUBJECT, 1_000L, "VIEW_ONLY,BOGUS_FUTURE_CAP", NOW + 60_000L);

        assertTrue(store().granted(key("s-bad", 1_000L), NOW).isEmpty(),
                "an unknown stored capability token grants nothing (fail-closed all-or-nothing)");
    }

    @Test
    void probeAvailableSucceedsWhenTheTableExists() {
        store().probeAvailable(); // no throw — the V67 table exists
    }
}
