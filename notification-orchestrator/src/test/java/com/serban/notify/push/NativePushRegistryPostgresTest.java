package com.serban.notify.push;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Uses real PostgreSQL constraints/transactions, never a mocked ownership store. */
@Testcontainers
class NativePushRegistryPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private NativePushRegistry registry;
    private TransactionTemplate tx;
    private static final String APP = "com.example.test";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @BeforeEach void setUp() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS notify CASCADE");
        jdbc.execute("CREATE SCHEMA notify");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V30__native_push_registration.sql")).execute(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        registry = new NativePushRegistry(jdbc, KEY, new NativePushScopePolicy(APP + "/FCM/TEST"));
    }

    private UUID register(String org, String owner, UUID installation, String token) {
        return tx.execute(status -> registry.register(org, owner, installation, APP, "FCM", "TEST", token));
    }

    @Test void rotationKeepsIdAndOnlyEncryptedTokenSurvivesRestart() {
        UUID installation = UUID.randomUUID();
        UUID id = register("org", "alice", installation, "old-synthetic-token");
        assertEquals(id, register("org", "alice", installation, "new-synthetic-token"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notify.native_push_registration", Integer.class));
        String ciphertext = jdbc.queryForObject("SELECT token_ciphertext FROM notify.native_push_registration", String.class);
        assertFalse(ciphertext.contains("synthetic-token"));
        assertEquals("new-synthetic-token", new NativePushTokenCipher(Base64.getDecoder().decode(KEY)).decrypt(ciphertext, id.toString()));
        registry = new NativePushRegistry(jdbc, KEY, new NativePushScopePolicy(APP + "/FCM/TEST"));
        assertEquals(id, register("org", "alice", installation, "new-synthetic-token"));
    }

    @Test void crossOwnerTakeoverFailsAndLogoutAllowsExplicitNewRegistration() {
        UUID installation = UUID.randomUUID();
        UUID id = register("org", "alice", installation, "synthetic-token");
        assertThrows(ResponseStatusException.class, () -> register("org", "bob", installation, "synthetic-token"));
        assertThrows(ResponseStatusException.class, () -> register("other-org", "alice", installation, "synthetic-token"));
        registry.remove("org", "bob", id);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notify.native_push_registration", Integer.class));
        registry.remove("org", "alice", id);
        registry.remove("org", "alice", id);
        assertNotEquals(id, register("org", "bob", installation, "synthetic-token"));
    }

    @Test void tokenCannotBeBoundToTwoInstallations() {
        register("org", "alice", UUID.randomUUID(), "synthetic-token");
        var failure = assertThrows(ResponseStatusException.class, () -> register("org", "bob", UUID.randomUUID(), "synthetic-token"));
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("synthetic-token"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notify.native_push_registration", Integer.class));
    }

    @Test void failedTransactionDoesNotLeaveRegistrationAndRetryCanPersist() {
        UUID installation = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            registry.register("org", "alice", installation, APP, "FCM", "TEST", "synthetic-token");
            throw new IllegalStateException("synthetic rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notify.native_push_registration", Integer.class));
        assertNotNull(register("org", "alice", installation, "synthetic-token"));
    }

    @Test void concurrentRegistrationIsIdempotent() throws Exception {
        UUID installation = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> register("org", "alice", installation, "synthetic-token"));
            var second = executor.submit(() -> register("org", "alice", installation, "synthetic-token"));
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notify.native_push_registration", Integer.class));
    }

    @Test void deliveryIsOwnerScopedAndStaleInvalidationPreservesRotatedToken() {
        UUID installation = UUID.randomUUID();
        UUID id = register("org", "alice", installation, "synthetic-token");
        assertTrue(registry.forDelivery(id, "org", "bob").isEmpty());
        var old = registry.forDelivery(id, "org", "alice").orElseThrow();
        register("org", "alice", installation, "rotated-token");
        registry.invalidate(id, old.tokenHash());
        assertEquals("rotated-token", registry.forDelivery(id, "org", "alice").orElseThrow().token());
        tx.executeWithoutResult(status -> registry.eraseOwner("org", "bob"));
        assertEquals(1, registry.targets("org", "alice").size());
        tx.executeWithoutResult(status -> registry.eraseOwner("org", "alice"));
        assertTrue(registry.targets("org", "alice").isEmpty());
    }

    @Test void cancellationWithoutResponseIdIsOwnerScoped() {
        UUID installation = UUID.randomUUID();
        register("org", "alice", installation, "synthetic-token");
        tx.executeWithoutResult(status -> registry.removeInstallation("org", "bob", installation, APP, "FCM", "TEST"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notify.native_push_registration", Integer.class));
        tx.executeWithoutResult(status -> registry.removeInstallation("org", "alice", installation, APP, "FCM", "TEST"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notify.native_push_registration", Integer.class));
    }

    @Test void logoutWaitsForRegistrationAndCannotBeUndoneByThatRegistration() throws Exception {
        UUID installation = UUID.randomUUID();
        UUID id = register("org", "alice", installation, "synthetic-token");
        var acquired = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var updating = executor.submit(() -> tx.execute(status -> {
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, APP + "/FCM/TEST/" + installation);
                acquired.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return registry.register("org", "alice", installation, APP, "FCM", "TEST", "rotated-token");
            }));
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            var deleting = executor.submit(() -> tx.executeWithoutResult(status -> registry.remove("org", "alice", id)));
            try { assertThrows(java.util.concurrent.TimeoutException.class, () -> deleting.get(200, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            updating.get(5, TimeUnit.SECONDS);
            deleting.get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notify.native_push_registration", Integer.class));
    }
}
