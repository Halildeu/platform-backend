package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class JdbcViewOnlyCheckpointCasTransactionFailureTest {
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final String D1 = "sha256:" + "1".repeat(64);
    private static final String D2 = "sha256:" + "2".repeat(64);
    private static final String D3 = "sha256:" + "3".repeat(64);
    private static final String D4 = "sha256:" + "4".repeat(64);
    private static final String D5 = "sha256:" + "5".repeat(64);

    @Test
    void transactionBeginFailureUsesStrictStoreUnavailableContract() {
        PlatformTransactionManager failing = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                throw new CannotCreateTransactionException("unit begin failure");
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };

        assertStoreUnavailable(cas(mock(JdbcTemplate.class), failing)::registerLease);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ambiguousCommitFailureUsesStrictStoreUnavailableContract() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(Class.class), any())).thenReturn("locked");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AbstractPlatformTransactionManager commitFailure = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                throw new TransactionSystemException("ambiguous commit");
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };

        assertStoreUnavailable(cas(jdbc, commitFailure)::registerLease);
    }

    private static JdbcViewOnlyCheckpointCas cas(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcViewOnlyCheckpointCas(
                jdbc, transactions, new RemoteViewJsonCanonicalizer(),
                Clock.fixed(NOW, ZoneOffset.UTC), "endpoint_admin_service");
    }

    private static void assertStoreUnavailable(java.util.function.Function<ViewOnlyLeaseRecord, byte[]> call) {
        RemoteViewJsonCanonicalizer canonicalizer = new RemoteViewJsonCanonicalizer();
        ViewOnlyLeaseRecord lease = new ViewOnlyLeaseRecord(
                UUID.fromString("123e4567-e89b-42d3-a456-426614174001"),
                UUID.fromString("123e4567-e89b-42d3-a456-426614174002"),
                D1, D2, D3, D4, D5, ViewOnlyTestFixtures.binding(canonicalizer, 1, 1),
                D1, D2, D3, D4, new byte[]{1}, NOW, NOW.plusSeconds(900), 64);
        assertThatThrownBy(() -> call.apply(lease))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.CHECKPOINT_STORE_UNAVAILABLE);
    }
}
