package com.example.endpointadmin.remoteaccess;

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

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Faz 22.6 B2.2c — TTL cleanup IT: purge expired non-REVOKED rows, keep REVOKED for audit (Codex #5/#10).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RemoteSessionTokenCleanupPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");

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

    private void insert(String jti, String state, Instant expiresAt) {
        jdbc.update("INSERT INTO " + SCHEMA + ".remote_session_token (jti, state, expires_at, created_at) "
                + "VALUES (?, ?, ?, ?)", jti, state, java.sql.Timestamp.from(expiresAt),
                java.sql.Timestamp.from(T0));
    }

    private int count(String jti) {
        return jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".remote_session_token WHERE jti = ?",
                Integer.class, jti);
    }

    @Test
    void purgesExpiredNonRevokedButKeepsRevokedAndLive() {
        Instant past = T0.minus(Duration.ofHours(2));
        Instant future = T0.plus(Duration.ofHours(2));
        insert("expired-used", "USED", past);        // expired + not revoked → purged
        insert("expired-expired", "EXPIRED", past);  // expired + not revoked → purged
        insert("expired-revoked", "REVOKED", past);  // expired BUT revoked → KEPT (audit)
        insert("live-used", "USED", future);         // not expired → kept

        RemoteSessionTokenCleanup cleanup = new RemoteSessionTokenCleanup(jdbc, SCHEMA);
        int purged = cleanup.purgeExpired(T0);

        assertEquals(2, purged); // only the two expired non-revoked rows
        assertEquals(0, count("expired-used"));
        assertEquals(0, count("expired-expired"));
        assertEquals(1, count("expired-revoked"), "REVOKED row must survive for audit");
        assertEquals(1, count("live-used"), "live token must survive");
    }

    @Test
    void nullClockPurgesNothing() {
        insert("x", "EXPIRED", T0.minus(Duration.ofHours(1)));
        assertEquals(0, new RemoteSessionTokenCleanup(jdbc, SCHEMA).purgeExpired(null));
        assertEquals(1, count("x"));
    }
}
