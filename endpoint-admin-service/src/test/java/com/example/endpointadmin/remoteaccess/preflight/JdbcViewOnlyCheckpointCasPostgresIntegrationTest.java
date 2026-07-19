package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JdbcViewOnlyCheckpointCasPostgresIntegrationTest {
    private static final String SCHEMA = "endpoint_admin_service";
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final UUID LEASE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174001");
    private static final UUID REDEEM_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174002");
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String REF = "refs/tags/cross-ai-intent/123e4567-e89b-42d3-a456-426614174000";
    private static final String D1 = "sha256:" + "1".repeat(64);
    private static final String D2 = "sha256:" + "2".repeat(64);
    private static final String D3 = "sha256:" + "3".repeat(64);
    private static final String D4 = "sha256:" + "4".repeat(64);
    private static final String D5 = "sha256:" + "5".repeat(64);
    private static final String D6 = "sha256:" + "6".repeat(64);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
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

    private RemoteViewJsonCanonicalizer canonicalizer;
    private JdbcViewOnlyCheckpointCas cas;
    private ViewOnlyOidcCaller caller;

    @BeforeEach
    void setUp() {
        canonicalizer = new RemoteViewJsonCanonicalizer();
        cas = new JdbcViewOnlyCheckpointCas(
                jdbc, transactionManager, canonicalizer, Clock.fixed(NOW, ZoneOffset.UTC), SCHEMA);
        caller = new ViewOnlyOidcCaller(
                "executor", "https://token.actions.githubusercontent.com",
                "repo:Halildeu/platform-k8s-gitops:ref:" + REF,
                186576227L, 1211415632L, 29678094664L, 1, REF, SHA, D6);
    }

    @Test
    void leaseExactRetryReturnsFirstCommittedBytes() {
        byte[] first = cas.registerLease(lease(D1, "first-lease"));
        byte[] retry = cas.registerLease(lease(D1, "different-resign-output"));

        assertThat(text(first)).isEqualTo("first-lease");
        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + ".view_only_checkpoint_leases", Integer.class)).isEqualTo(1);
    }

    @Test
    void leaseSameKeyDifferentBodyIsConflict() {
        cas.registerLease(lease(D1, "first-lease"));
        ViewOnlyLeaseRecord changed = new ViewOnlyLeaseRecord(
                LEASE_ID, REDEEM_ID, D1, D6, D2, D3, D4,
                binding(), D5, D1, D2, D3, bytes("other"), NOW, NOW.plusSeconds(900), 64);

        assertReason(ViewOnlyAuthorityError.IDEMPOTENCY_CONFLICT, () -> cas.registerLease(changed));
    }

    @Test
    void checkpointChainPersistsByteIdenticalRetryAndOrderedTransition() {
        cas.registerLease(lease(D1, "lease"));
        AtomicInteger signatures = new AtomicInteger();
        ViewOnlyCheckpointReceiptSigner signer = input -> bytes("receipt-" + signatures.incrementAndGet());

        ViewOnlyCheckpointCommand initial = command(
                0, null, ViewOnlyCheckpointState.DECISION_AUTHORIZED, null, D1, false);
        byte[] first = cas.createCheckpoint(initial, caller, signer);
        byte[] retry = cas.createCheckpoint(initial, caller, signer);
        ViewOnlyCheckpointCommand revalidated = command(
                1, ViewOnlyCheckpointState.DECISION_AUTHORIZED, ViewOnlyCheckpointState.LIVE_REVALIDATED,
                D1, D2, false);
        byte[] second = cas.createCheckpoint(revalidated, caller, signer);

        assertThat(text(first)).isEqualTo("receipt-1");
        assertThat(retry).isEqualTo(first);
        assertThat(text(second)).isEqualTo("receipt-2");
        assertThat(signatures).hasValue(2);
        assertThat(cas.readCheckpoint(D3, 1, caller)).isEqualTo(second);
        assertThat(jdbc.queryForObject(
                "SELECT write_count FROM " + SCHEMA + ".view_only_checkpoint_leases WHERE lease_id = ?",
                Integer.class, LEASE_ID)).isEqualTo(2);
    }

    @Test
    void sameSequenceDifferentRequestAndExecutorAreRejected() {
        cas.registerLease(lease(D1, "lease"));
        cas.createCheckpoint(command(0, null, ViewOnlyCheckpointState.DECISION_AUTHORIZED, null, D1, false),
                caller, input -> bytes("receipt"));

        ViewOnlyCheckpointCommand sequenceConflict = new ViewOnlyCheckpointCommand(
                UUID.fromString("123e4567-e89b-42d3-a456-426614174099"), LEASE_ID, D5, D3, D4,
                0, null, ViewOnlyCheckpointState.DECISION_AUTHORIZED, "decision-authorized",
                D1, D2, null, D2, D6, D2, caller.stableIdentitySha256(canonicalizer), false, NOW);
        assertReason(ViewOnlyAuthorityError.SEQUENCE_CONFLICT,
                () -> cas.createCheckpoint(sequenceConflict, caller, input -> bytes("other")));

        ViewOnlyOidcCaller foreign = new ViewOnlyOidcCaller(
                "executor", caller.issuer(), caller.subject(), caller.actorId(), caller.repositoryId(),
                caller.runId() + 1, 1, caller.ref(), caller.headSha(), D5);
        ViewOnlyCheckpointCommand next = command(
                1, ViewOnlyCheckpointState.DECISION_AUTHORIZED, ViewOnlyCheckpointState.LIVE_REVALIDATED,
                D1, D2, false);
        assertReason(ViewOnlyAuthorityError.EXECUTOR_IDENTITY_MISMATCH,
                () -> cas.createCheckpoint(next, foreign, input -> bytes("other")));
    }

    @Test
    void invalidTransitionAndTerminalCloseFailClosed() {
        cas.registerLease(lease(D1, "lease"));
        cas.createCheckpoint(command(0, null, ViewOnlyCheckpointState.DECISION_AUTHORIZED, null, D1, false),
                caller, input -> bytes("r0"));
        ViewOnlyCheckpointCommand skip = command(
                1, ViewOnlyCheckpointState.DECISION_AUTHORIZED, ViewOnlyCheckpointState.ACTIVATED,
                D1, D2, false);
        assertReason(ViewOnlyAuthorityError.STATE_TRANSITION_DENIED,
                () -> cas.createCheckpoint(skip, caller, input -> bytes("bad")));

        append(1, ViewOnlyCheckpointState.DECISION_AUTHORIZED, ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED, D1, D2);
        append(2, ViewOnlyCheckpointState.ARTIFACTS_STAGE_FAILED, ViewOnlyCheckpointState.ROLLBACK_PENDING, D2, D3);
        append(3, ViewOnlyCheckpointState.ROLLBACK_PENDING, ViewOnlyCheckpointState.ROLLED_BACK, D3, D4);
        ViewOnlyCheckpointCommand terminal = command(
                4, ViewOnlyCheckpointState.ROLLED_BACK, ViewOnlyCheckpointState.FAILED_CLEAN, D4, D5, true);
        byte[] terminalReceipt = cas.createCheckpoint(terminal, caller, input -> bytes("terminal"));
        assertThat(cas.createCheckpoint(terminal, caller, input -> bytes("resigned"))).isEqualTo(terminalReceipt);

        ViewOnlyCheckpointCommand afterClose = command(
                5, ViewOnlyCheckpointState.FAILED_CLEAN, ViewOnlyCheckpointState.COMPLETED, D5, D6, true);
        assertReason(ViewOnlyAuthorityError.LEASE_CLOSED,
                () -> cas.createCheckpoint(afterClose, caller, input -> bytes("never")));
    }

    @Test
    void committedLeaseAndCheckpointRemainRecoverableAfterExpiry() {
        byte[] leaseBytes = cas.registerLease(lease(D1, "lease"));
        ViewOnlyCheckpointCommand initial = command(
                0, null, ViewOnlyCheckpointState.DECISION_AUTHORIZED, null, D1, false);
        byte[] checkpointBytes = cas.createCheckpoint(initial, caller, input -> bytes("checkpoint"));
        JdbcViewOnlyCheckpointCas afterExpiry = new JdbcViewOnlyCheckpointCas(
                jdbc, transactionManager, canonicalizer,
                Clock.fixed(NOW.plusSeconds(901), ZoneOffset.UTC), SCHEMA);

        assertThat(afterExpiry.findLeaseRetry(
                REDEEM_ID, D1, D1, D2, D3, D3))
                .hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(leaseBytes));
        assertThat(afterExpiry.findCheckpointRetry(new ViewOnlyCheckpointRetryCandidate(
                initial.requestId(), initial.idempotencyKeySha256(), initial.requestBodySha256(),
                initial.executorIdentitySha256(), initial.leaseEnvelopeSha256(),
                initial.transactionIdSha256(), initial.sequence(), initial.storedObjectSha256())))
                .hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(checkpointBytes));
        assertThat(afterExpiry.readCheckpoint(D3, 0, caller)).isEqualTo(checkpointBytes);
    }

    @Test
    void checkpointEvidenceIsDatabaseEnforcedWorm() {
        cas.registerLease(lease(D1, "lease"));
        cas.createCheckpoint(command(
                        0, null, ViewOnlyCheckpointState.DECISION_AUTHORIZED, null, D1, false),
                caller, input -> bytes("checkpoint"));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE " + SCHEMA + ".view_only_external_checkpoints SET reason_code = ?",
                "rewritten"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void leaseAuthorityProjectionCannotBeRewritten() {
        cas.registerLease(lease(D1, "lease"));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE " + SCHEMA + ".view_only_checkpoint_leases SET binding_sha256 = ? WHERE lease_id = ?",
                D6, LEASE_ID))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("authority columns are immutable");
    }

    @Test
    void checkpointForeignKeyPinsTheCompleteLeaseAuthorityProjection() {
        String definition = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                        + " WHERE conname = 'fk_vo_checkpoint_exact_lease_authority'"
                        + " AND connamespace = ?::regnamespace",
                String.class, SCHEMA);

        assertThat(definition)
                .contains("lease_id, transaction_id_sha256, binding_sha256, lease_envelope_sha256")
                .contains("expires_at, executor_identity_sha256");
    }

    private void append(int sequence,
                        ViewOnlyCheckpointState from,
                        ViewOnlyCheckpointState to,
                        String previousDigest,
                        String storedDigest) {
        cas.createCheckpoint(command(sequence, from, to, previousDigest, storedDigest, false),
                caller, input -> bytes("r" + sequence));
    }

    private ViewOnlyLeaseRecord lease(String idempotencyKey, String response) {
        return new ViewOnlyLeaseRecord(
                LEASE_ID, REDEEM_ID, idempotencyKey, D1, D2, D3, D4, binding(), D5,
                D1, D2, D3, bytes(response), NOW, NOW.plusSeconds(900), 64);
    }

    private ObjectNode binding() {
        return canonicalizer.mapper().createObjectNode()
                .put("triggeringActorId", 186576227L)
                .put("runId", 29678094664L)
                .put("runAttempt", 1)
                .put("intentRef", REF)
                .put("headSha", SHA);
    }

    private ViewOnlyCheckpointCommand command(int sequence,
                                              ViewOnlyCheckpointState previous,
                                              ViewOnlyCheckpointState state,
                                              String previousDigest,
                                              String storedDigest,
                                              boolean terminal) {
        String sequenceHex = Integer.toHexString(sequence + 10);
        String requestId = "123e4567-e89b-42d3-a456-4266141740" + String.format("%02d", sequence + 10);
        String key = "sha256:" + sequenceHex.repeat(64).substring(0, 64);
        String body = "sha256:" + Integer.toHexString(sequence + 1).repeat(64).substring(0, 64);
        return new ViewOnlyCheckpointCommand(
                UUID.fromString(requestId), LEASE_ID, D5, D3, D4, sequence, previous, state,
                state.name().toLowerCase().replace('_', '-'), D1, D2, previousDigest, storedDigest,
                key, body, caller.stableIdentitySha256(canonicalizer), terminal, NOW);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static void assertReason(ViewOnlyAuthorityError reason, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(reason);
    }
}
