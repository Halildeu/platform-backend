package com.example.permission.outbox;

import com.example.permission.service.TupleSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TupleSyncOutboxPoller.
 * Verifies poll-and-process lifecycle with mocked repository and sync service.
 */
@ExtendWith(MockitoExtension.class)
class TupleSyncOutboxPollerTest {

    @Mock
    TupleSyncOutboxRepository outboxRepository;

    @Mock
    TupleSyncService tupleSyncService;

    @InjectMocks
    TupleSyncOutboxPoller poller;

    private TupleSyncOutboxEntry createEntry(Long roleId) {
        return new TupleSyncOutboxEntry(roleId);
    }

    @Nested
    @DisplayName("pollAndProcess — empty queue")
    class EmptyQueue {

        @Test
        @DisplayName("no-op when no pending entries exist")
        void noPendingEntries() {
            when(outboxRepository.findPendingForUpdate(anyInt()))
                    .thenReturn(Collections.emptyList());

            poller.pollAndProcess();

            verify(outboxRepository).findPendingForUpdate(anyInt());
            verifyNoInteractions(tupleSyncService);
            verify(outboxRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("pollAndProcess — successful sync")
    class SuccessfulSync {

        @Test
        @DisplayName("processes single entry and marks DONE")
        void singleEntrySuccess() {
            TupleSyncOutboxEntry entry = createEntry(42L);
            when(outboxRepository.findPendingForUpdate(anyInt()))
                    .thenReturn(List.of(entry));

            poller.pollAndProcess();

            verify(tupleSyncService).propagateRoleChange(42L);
            // save called twice: once for PROCESSING, once for DONE
            verify(outboxRepository, times(2)).save(entry);
            assertEquals("DONE", entry.getStatus());
        }

        @Test
        @DisplayName("processes multiple entries in order")
        void multipleEntriesSuccess() {
            TupleSyncOutboxEntry entry1 = createEntry(10L);
            TupleSyncOutboxEntry entry2 = createEntry(20L);
            TupleSyncOutboxEntry entry3 = createEntry(30L);

            when(outboxRepository.findPendingForUpdate(anyInt()))
                    .thenReturn(List.of(entry1, entry2, entry3));

            poller.pollAndProcess();

            var inOrder = inOrder(tupleSyncService);
            inOrder.verify(tupleSyncService).propagateRoleChange(10L);
            inOrder.verify(tupleSyncService).propagateRoleChange(20L);
            inOrder.verify(tupleSyncService).propagateRoleChange(30L);

            assertEquals("DONE", entry1.getStatus());
            assertEquals("DONE", entry2.getStatus());
            assertEquals("DONE", entry3.getStatus());
        }
    }

    @Nested
    @DisplayName("pollAndProcess — failure handling")
    class FailureHandling {

        @Test
        @DisplayName("marks entry PENDING on first failure (retry)")
        void firstFailureRetries() {
            TupleSyncOutboxEntry entry = createEntry(99L);
            when(outboxRepository.findPendingForUpdate(anyInt()))
                    .thenReturn(List.of(entry));
            doThrow(new RuntimeException("OpenFGA timeout"))
                    .when(tupleSyncService).propagateRoleChange(99L);

            poller.pollAndProcess();

            verify(outboxRepository, times(2)).save(entry);
            // After 1 attempt (< maxAttempts=5), status goes back to PENDING
            assertEquals("PENDING", entry.getStatus());
            assertEquals(1, entry.getAttempts());
            assertEquals("OpenFGA timeout", entry.getErrorMessage());
        }

        @Test
        @DisplayName("marks entry FAILED after max attempts exhausted")
        void maxAttemptsExhausted() {
            TupleSyncOutboxEntry entry = createEntry(77L);
            // Simulate 4 previous attempts (markProcessing increments to 5 = maxAttempts)
            for (int i = 0; i < 4; i++) {
                entry.markProcessing();
                entry.markFailed("previous failure");
            }
            assertEquals(4, entry.getAttempts());
            assertEquals("PENDING", entry.getStatus());

            when(outboxRepository.findPendingForUpdate(anyInt()))
                    .thenReturn(List.of(entry));
            doThrow(new RuntimeException("still failing"))
                    .when(tupleSyncService).propagateRoleChange(77L);

            poller.pollAndProcess();

            // 5th attempt => FAILED (dead letter)
            assertEquals("FAILED", entry.getStatus());
            assertEquals(5, entry.getAttempts());
        }

        @Test
        @DisplayName("continues processing remaining entries after one fails")
        void continuePastFailure() {
            TupleSyncOutboxEntry entry1 = createEntry(1L);
            TupleSyncOutboxEntry entry2 = createEntry(2L);

            when(outboxRepository.findPendingForUpdate(anyInt()))
                    .thenReturn(List.of(entry1, entry2));
            doThrow(new RuntimeException("fail"))
                    .when(tupleSyncService).propagateRoleChange(1L);
            // entry2 succeeds (no throw)

            poller.pollAndProcess();

            assertEquals("PENDING", entry1.getStatus()); // retried
            assertEquals("DONE", entry2.getStatus());     // succeeded
        }
    }

    @Nested
    @DisplayName("TupleSyncOutboxEntry — state transitions")
    class EntryStateMachine {

        @Test
        @DisplayName("new entry starts PENDING with 0 attempts")
        void newEntryState() {
            TupleSyncOutboxEntry entry = new TupleSyncOutboxEntry(1L);
            assertEquals("PENDING", entry.getStatus());
            assertEquals(0, entry.getAttempts());
            assertEquals(1L, entry.getRoleId());
        }

        @Test
        @DisplayName("markProcessing increments attempts")
        void markProcessing() {
            TupleSyncOutboxEntry entry = new TupleSyncOutboxEntry(1L);
            entry.markProcessing();
            assertEquals("PROCESSING", entry.getStatus());
            assertEquals(1, entry.getAttempts());
        }

        @Test
        @DisplayName("markDone clears error message")
        void markDone() {
            TupleSyncOutboxEntry entry = new TupleSyncOutboxEntry(1L);
            entry.markProcessing();
            entry.markFailed("some error");
            entry.markProcessing();
            entry.markDone();
            assertEquals("DONE", entry.getStatus());
            assertNull(entry.getErrorMessage());
        }

        @Test
        @DisplayName("markFailed truncates long error messages to 500 chars")
        void errorTruncation() {
            TupleSyncOutboxEntry entry = new TupleSyncOutboxEntry(1L);
            entry.markProcessing();
            String longError = "x".repeat(1000);
            entry.markFailed(longError);
            assertEquals(500, entry.getErrorMessage().length());
        }
    }
}
