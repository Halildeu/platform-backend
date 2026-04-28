package com.example.permission.dataaccess;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Faz 21.3 PR-G — Mockito unit test for {@link OutboxPoller} after the
 * Codex 019dd0e0 iter-2 BLOCKER 3 absorb. The poller no longer mutates
 * entry state in-memory then calls {@code save()}; instead it routes
 * every finalize through one of the CAS-fenced repository UPDATEs
 * ({@link DataAccessScopeOutboxRepository#markProcessed},
 * {@link DataAccessScopeOutboxRepository#markRetry},
 * {@link DataAccessScopeOutboxRepository#markFailed}). The tests assert
 * each path invokes the right native method with the lock token from
 * the claim, and that a CAS miss (rows-affected = 0) is logged but does
 * not raise.
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

        // CAS UPDATEs default to 1 row affected (success).
        when(outboxRepository.markProcessed(anyLong(), any(), anyString(), any())).thenReturn(1);
        when(outboxRepository.markRetry(anyLong(), any(), any(), anyString(), any())).thenReturn(1);
        when(outboxRepository.markFailed(anyLong(), any(), anyString(), any())).thenReturn(1);

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
    void processEntry_grantHappyPath_writesTupleAndCASMarksProcessed() {
        Instant lockToken = Instant.parse("2026-04-28T12:02:00Z");
        DataAccessScopeOutboxEntry entry = grantEntry(101L, 1, "wc-our-company-1", "company");

        poller.processEntry(entry, lockToken);

        verify(authzService).writeTuple(
                eq("11111111-1111-1111-1111-111111111111"),
                eq("viewer"),
                eq("company"),
                eq("wc-our-company-1"));
        verify(outboxRepository).markProcessed(eq(101L), any(Instant.class), eq("test-poller"), eq(lockToken));
        verify(outboxRepository, never()).markRetry(anyLong(), any(), any(), anyString(), any());
        verify(outboxRepository, never()).markFailed(anyLong(), any(), anyString(), any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void processEntry_revokeHappyPath_deletesTupleAndCASMarksProcessed() {
        Instant lockToken = Instant.parse("2026-04-28T12:02:00Z");
        DataAccessScopeOutboxEntry entry = revokeEntry(102L, 1, "wc-our-company-1", "company");

        poller.processEntry(entry, lockToken);

        verify(authzService).deleteTuple(
                eq("11111111-1111-1111-1111-111111111111"),
                eq("viewer"),
                eq("company"),
                eq("wc-our-company-1"));
        verify(outboxRepository).markProcessed(eq(102L), any(Instant.class), eq("test-poller"), eq(lockToken));
    }

    @Test
    void processEntry_fgaFailure_belowMaxAttempts_CASSchedulesRetry() {
        Instant lockToken = Instant.parse("2026-04-28T12:02:00Z");
        DataAccessScopeOutboxEntry entry = grantEntry(103L, 1, "wc-our-company-1", "company");
        doThrow(new RuntimeException("openfga down"))
                .when(authzService).writeTuple(any(), any(), any(), any());
        Instant nextAt = Instant.parse("2026-04-28T12:00:30Z");
        when(backoffPolicy.nextAttemptAt(anyInt(), any(), any())).thenReturn(nextAt);

        poller.processEntry(entry, lockToken);

        verify(outboxRepository).markRetry(eq(103L), eq(nextAt),
                org.mockito.ArgumentMatchers.contains("openfga down"),
                eq("test-poller"), eq(lockToken));
        verify(outboxRepository, never()).markProcessed(anyLong(), any(), anyString(), any());
        verify(outboxRepository, never()).markFailed(anyLong(), any(), anyString(), any());
    }

    @Test
    void processEntry_fgaFailure_atMaxAttempts_CASMarksTerminalFailed() {
        Instant lockToken = Instant.parse("2026-04-28T12:02:00Z");
        DataAccessScopeOutboxEntry entry = grantEntry(104L, 3, "wc-our-company-1", "company");
        // attemptCount 3 == maxAttempts 3 — terminal on next failure
        doThrow(new RuntimeException("openfga gone"))
                .when(authzService).writeTuple(any(), any(), any(), any());

        poller.processEntry(entry, lockToken);

        verify(outboxRepository).markFailed(eq(104L),
                org.mockito.ArgumentMatchers.contains("openfga gone"),
                eq("test-poller"), eq(lockToken));
        verify(backoffPolicy, never()).nextAttemptAt(anyInt(), any(), any());
        verify(outboxRepository, never()).markProcessed(anyLong(), any(), anyString(), any());
        verify(outboxRepository, never()).markRetry(anyLong(), any(), any(), anyString(), any());
    }

    @Test
    void processEntry_casMissOnFinalize_logsButDoesNotThrow() {
        Instant lockToken = Instant.parse("2026-04-28T12:02:00Z");
        DataAccessScopeOutboxEntry entry = grantEntry(105L, 1, "wc-our-company-1", "company");
        // Simulate stale-worker: another poller already reclaimed and
        // finalized the row, so our CAS UPDATE matches no rows.
        when(outboxRepository.markProcessed(anyLong(), any(), anyString(), any()))
                .thenReturn(0);

        poller.processEntry(entry, lockToken);

        verify(authzService).writeTuple(any(), any(), any(), any());
        verify(outboxRepository).markProcessed(eq(105L), any(Instant.class), eq("test-poller"), eq(lockToken));
        // No exception must escape — the test simply reaching this assertion
        // is the contract.
    }

    @Test
    void pollAndProcess_recoversStuckRows_logsCount() {
        when(outboxRepository.recoverStuckRows()).thenReturn(3);
        when(outboxRepository.claimBatch(any(), any(), anyInt())).thenReturn(List.of());

        poller.pollAndProcess();

        verify(outboxRepository, times(1)).recoverStuckRows();
    }

    @Test
    void pollAndProcess_claimedBatch_processesEachWithItsLockToken() {
        Instant tokenE1 = Instant.parse("2026-04-28T12:02:00Z");
        Instant tokenE2 = Instant.parse("2026-04-28T12:02:01Z");
        DataAccessScopeOutboxEntry e1 = grantEntry(201L, 1, "wc-our-company-1", "company");
        e1.setLockedUntil(tokenE1);
        DataAccessScopeOutboxEntry e2 = revokeEntry(202L, 1, "wc-project-1204", "project");
        e2.setLockedUntil(tokenE2);
        when(outboxRepository.recoverStuckRows()).thenReturn(0);
        when(outboxRepository.claimBatch(any(), any(), anyInt())).thenReturn(List.of(e1, e2));

        poller.pollAndProcess();

        verify(authzService).writeTuple(any(), any(), eq("company"), eq("wc-our-company-1"));
        verify(authzService).deleteTuple(any(), any(), eq("project"), eq("wc-project-1204"));
        // Each entry's CAS finalize must use ITS OWN lock token from the claim,
        // not a pollAndProcess-wide value — Codex 019dd0e0 iter-2 BLOCKER 3.
        verify(outboxRepository).markProcessed(eq(201L), any(Instant.class), eq("test-poller"), eq(tokenE1));
        verify(outboxRepository).markProcessed(eq(202L), any(Instant.class), eq("test-poller"), eq(tokenE2));
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
        // V23 typed tuple identity columns + composite tuple_object.
        e.setTupleUser("user:11111111-1111-1111-1111-111111111111");
        e.setTupleRelation("viewer");
        e.setTupleObject(objectType + ":" + objectId);

        Map<String, Object> tuple = new LinkedHashMap<>();
        tuple.put("user", "user:11111111-1111-1111-1111-111111111111");
        tuple.put("relation", "viewer");
        tuple.put("object", objectType + ":" + objectId);
        tuple.put("objectType", objectType);
        tuple.put("objectId", objectId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tuple", tuple);
        e.setPayload(payload);
        return e;
    }
}
