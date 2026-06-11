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
 * Faz 22.6 B2.2b — DB-CAS store contract against a real PostgreSQL (Testcontainers + Flyway V63). The
 * single-use atomicity is a Postgres {@code INSERT ... ON CONFLICT} guarantee; these sequential contract
 * tests prove the SQL/state mapping + revoke/expire ordering + DB-authoritative time. A committed-
 * connection concurrency load test (exactly-one-ACCEPTED under N threads) is the B2.2c SLO harness.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DbCasTokenLifecycleStorePostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant EXP = T0.plus(Duration.ofHours(1));

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

    private DbCasTokenLifecycleStore store() {
        return new DbCasTokenLifecycleStore(jdbc, SCHEMA);
    }

    @Test
    void consumeAcceptsOnceThenReplayDenied() {
        DbCasTokenLifecycleStore s = store();
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ACCEPTED, s.consume("jti-1", EXP, T0));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.LIVE, s.isTokenLive("jti-1", T0));
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ALREADY_USED, s.consume("jti-1", EXP, T0));
    }

    @Test
    void alreadyExpiredConsumeIsRejected() {
        DbCasTokenLifecycleStore s = store();
        assertEquals(TokenLifecycleStore.ConsumeOutcome.EXPIRED, s.consume("jti-old", T0, T0.plusSeconds(1)));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.EXPIRED, s.isTokenLive("jti-old", T0.plusSeconds(1)));
    }

    @Test
    void revokeIsAuthoritativeAndIdempotent() {
        DbCasTokenLifecycleStore s = store();
        s.consume("jti-r", EXP, T0);
        assertEquals(TokenLifecycleStore.MutationOutcome.UPDATED, s.revoke("jti-r"));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.REVOKED, s.isTokenLive("jti-r", T0));
        assertEquals(TokenLifecycleStore.ConsumeOutcome.REVOKED, s.consume("jti-r", EXP, T0));
        assertEquals(TokenLifecycleStore.MutationOutcome.NOOP, s.revoke("jti-r")); // idempotent
    }

    @Test
    void preEmptiveRevokeOfUnseenJtiWinsOverLaterConsume() {
        DbCasTokenLifecycleStore s = store();
        // revoke a jti never consumed → pre-emptive REVOKED row; a later consume must be denied REVOKED.
        assertEquals(TokenLifecycleStore.MutationOutcome.UPDATED, s.revoke("jti-pre"));
        assertEquals(TokenLifecycleStore.ConsumeOutcome.REVOKED, s.consume("jti-pre", EXP, T0));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.REVOKED, s.isTokenLive("jti-pre", T0));
    }

    @Test
    void revokeWinsOverExpire() {
        DbCasTokenLifecycleStore s = store();
        s.consume("jti-x", EXP, T0);
        assertEquals(TokenLifecycleStore.MutationOutcome.UPDATED, s.expire("jti-x"));
        assertEquals(TokenLifecycleStore.MutationOutcome.UPDATED, s.revoke("jti-x"));
        assertEquals(TokenLifecycleStore.ConsumeOutcome.REVOKED, s.consume("jti-x", EXP, T0));
        assertEquals(TokenLifecycleStore.MutationOutcome.NOOP, s.expire("jti-x")); // expire won't override revoke
    }

    @Test
    void livenessIsTimeAuthoritativeWithoutACleanupPass() {
        DbCasTokenLifecycleStore s = store();
        s.consume("jti-t", EXP, T0);
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.LIVE, s.isTokenLive("jti-t", EXP.minusSeconds(1)));
        // now past expires_at → EXPIRED even though no expire() ran (DB-authoritative time decision)
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.EXPIRED, s.isTokenLive("jti-t", EXP.plusSeconds(1)));
    }

    @Test
    void unknownJtiIsNotFound() {
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.NOT_FOUND, store().isTokenLive("nope", T0));
    }

    @Test
    void blankArgsAreInvalidWithoutTouchingTheDb() {
        DbCasTokenLifecycleStore s = store();
        assertEquals(TokenLifecycleStore.ConsumeOutcome.INVALID, s.consume("  ", EXP, T0));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.INVALID, s.isTokenLive(" ", T0));
        assertEquals(TokenLifecycleStore.MutationOutcome.NOOP, s.revoke(null));
    }
}
