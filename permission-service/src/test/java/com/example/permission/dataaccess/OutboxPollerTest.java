package com.example.permission.dataaccess;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Faz 21.3 PR-G — Mockito unit test for {@link OutboxPoller}.
 *
 * <p>Real {@code TransactionTemplate} is used (constructed inside
 * {@code OutboxPoller}), with a mocked {@link PlatformTransactionManager}
 * that returns a stub {@link TransactionStatus} on
 * {@code getTransaction(...)}; commit/rollback are no-ops by default. This
 * keeps the poller's own TX boundary semantic in scope while letting us
 * mock the OpenFGA + repository sides.
 */
class OutboxPollerTest {

    private DataAccessScopeOutboxRepository outboxRepository;
    private OpenFgaAuthzService authzService;
    private OutboxBackoffPolicy backoffPolicy;
    private OutboxConfig config;
    private PlatformTransactionManager txManager;
    private Environment env;

    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(DataAccessScopeOutboxRepository.class);
        authzService = mock(OpenFgaAuthzService.class);
        backoffPolicy = mock(OutboxBackoffPolicy.class);
        config = new OutboxConfig();
        config.setBatchSize(25);
        config.setMaxAttempts(3);
        config.setInitialBackoff(Duration.ofSeconds(5));
        config.setMaxBackoff(Duration.ofMinutes(15));
        config.setProcessingLockTtl(Duration.ofMinutes(2));

        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        env = mock(Environment.class);
        when(env.getProperty("HOSTNAME")).thenReturn("test-poller");

        poller = new OutboxPoller(outboxRepository, authzService, backoffPolicy, config, txManager, env);
    }

    @Test
    void pollAndProcess_emptyClaim_doesNotInteractWithFga() {
        when(outboxRepository.recoverStuckRows()).thenReturn(0);
        when(outboxRepository.claimBatch(eq("test-poller"), any(), eq(25)))
                .thenReturn(List.of());

        poller.pollAndProcess();

        verify(authzService, never()).writeTuple(any(), any(), any(), any());
        verify(authzService, never()).deleteTuple(any(), any(), any(), any());
    }

    @Test
    void processEntry_grantHappyPath_writesTupleAndMarksProcessed() {
        DataAccessScopeOutboxEntry entry = grantEntry(101L, 1, "wc-company-1001", "company");

        poller.processEntry(entry);

        verify(authzService).writeTuple(
                eq("11111111-1111-1111-1111-111111111111"),
                eq("viewer"),
                eq("company"),
                eq("wc-company-1001"));
        ArgumentCaptor<DataAccessScopeOutboxEntry> capt = ArgumentCaptor.forClass(DataAccessScopeOutboxEntry.class);
        verify(outboxRepository).save(capt.capture());
        DataAccessScopeOutboxEntry saved = capt.getValue();
        assertThat(saved.getStatus()).isEqualTo(DataAccessScopeOutboxEntry.Status.PROCESSED);
        assertThat(saved.getProcessedAt()).isNotNull();
        assertThat(saved.getLockedBy()).isNull();
    }

    @Test
    void processEntry_revokeHappyPath_deletesTupleAndMarksProcessed() {
        DataAccessScopeOutboxEntry entry = revokeEntry(102L, 1, "wc-company-1001", "company");

        poller.processEntry(entry);

        verify(authzService).deleteTuple(
                eq("11111111-1111-1111-1111-111111111111"),
                eq("viewer"),
                eq("company"),
                eq("wc-company-1001"));
        ArgumentCaptor<DataAccessScopeOutboxEntry> capt = ArgumentCaptor.forClass(DataAccessScopeOutboxEntry.class);
        verify(outboxRepository).save(capt.capture());
        assertThat(capt.getValue().getStatus()).isEqualTo(DataAccessScopeOutboxEntry.Status.PROCESSED);
    }

    @Test
    void processEntry_fgaFailure_belowMaxAttempts_schedulesRetry() {
        DataAccessScopeOutboxEntry entry = grantEntry(103L, 1, "wc-company-1001", "company");
        doThrow(new RuntimeException("openfga down"))
                .when(authzService).writeTuple(any(), any(), any(), any());
        Instant nextAt = Instant.parse("2026-04-28T12:00:00Z");
        when(backoffPolicy.nextAttemptAt(anyInt(), any(), any())).thenReturn(nextAt);

        poller.processEntry(entry);

        ArgumentCaptor<DataAccessScopeOutboxEntry> capt = ArgumentCaptor.forClass(DataAccessScopeOutboxEntry.class);
        verify(outboxRepository).save(capt.capture());
        DataAccessScopeOutboxEntry saved = capt.getValue();
        assertThat(saved.getStatus()).isEqualTo(DataAccessScopeOutboxEntry.Status.PENDING);
        assertThat(saved.getNextAttemptAt()).isEqualTo(nextAt);
        assertThat(saved.getLastError()).contains("openfga down");
    }

    @Test
    void processEntry_fgaFailure_atMaxAttempts_marksTerminalFailed() {
        DataAccessScopeOutboxEntry entry = grantEntry(104L, 3, "wc-company-1001", "company");
        // attemptCount 3 == maxAttempts 3 — terminal on next failure
        doThrow(new RuntimeException("openfga gone"))
                .when(authzService).writeTuple(any(), any(), any(), any());

        poller.processEntry(entry);

        ArgumentCaptor<DataAccessScopeOutboxEntry> capt = ArgumentCaptor.forClass(DataAccessScopeOutboxEntry.class);
        verify(outboxRepository).save(capt.capture());
        DataAccessScopeOutboxEntry saved = capt.getValue();
        assertThat(saved.getStatus()).isEqualTo(DataAccessScopeOutboxEntry.Status.FAILED);
        assertThat(saved.getProcessedAt()).isNotNull();
        verify(backoffPolicy, never()).nextAttemptAt(anyInt(), any(), any());
    }

    @Test
    void pollAndProcess_recoversStuckRows_logsCount() {
        when(outboxRepository.recoverStuckRows()).thenReturn(3);
        when(outboxRepository.claimBatch(any(), any(), anyInt())).thenReturn(List.of());

        poller.pollAndProcess();

        verify(outboxRepository, times(1)).recoverStuckRows();
    }

    @Test
    void pollAndProcess_claimedBatch_processesEach() {
        DataAccessScopeOutboxEntry e1 = grantEntry(201L, 1, "wc-company-1001", "company");
        DataAccessScopeOutboxEntry e2 = revokeEntry(202L, 1, "wc-project-1204", "project");
        when(outboxRepository.recoverStuckRows()).thenReturn(0);
        when(outboxRepository.claimBatch(any(), any(), anyInt())).thenReturn(List.of(e1, e2));

        poller.pollAndProcess();

        verify(authzService).writeTuple(any(), any(), eq("company"), eq("wc-company-1001"));
        verify(authzService).deleteTuple(any(), any(), eq("project"), eq("wc-project-1204"));
        verify(outboxRepository, times(2)).save(any(DataAccessScopeOutboxEntry.class));
    }

    private static DataAccessScopeOutboxEntry grantEntry(Long id, int attemptCount, String objectId, String objectType) {
        return entry(id, attemptCount, DataAccessScopeOutboxEntry.Action.GRANT, objectId, objectType);
    }

    private static DataAccessScopeOutboxEntry revokeEntry(Long id, int attemptCount, String objectId, String objectType) {
        return entry(id, attemptCount, DataAccessScopeOutboxEntry.Action.REVOKE, objectId, objectType);
    }

    private static DataAccessScopeOutboxEntry entry(Long id, int attemptCount,
                                                     DataAccessScopeOutboxEntry.Action action,
                                                     String objectId,
                                                     String objectType) {
        var e = new DataAccessScopeOutboxEntry();
        e.setId(id);
        e.setScopeId(id - 100L);
        e.setAction(action);
        e.setStatus(DataAccessScopeOutboxEntry.Status.PROCESSING);
        e.setAttemptCount(attemptCount);
        e.setNextAttemptAt(Instant.now());
        e.setCreatedAt(Instant.now());

        Map<String, Object> tuple = new LinkedHashMap<>();
        tuple.put("user", "11111111-1111-1111-1111-111111111111");
        tuple.put("relation", "viewer");
        tuple.put("objectType", objectType);
        tuple.put("objectId", objectId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tuple", tuple);
        e.setPayload(payload);
        return e;
    }
}
