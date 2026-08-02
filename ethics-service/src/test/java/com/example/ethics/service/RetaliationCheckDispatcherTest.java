package com.example.ethics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.model.RetaliationCheck;
import com.example.ethics.repository.RetaliationCheckRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** ES-213 (#3375) — the question actually reaching the reporter. */
class RetaliationCheckDispatcherTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-05T09:00:00Z");

    private final RetaliationCheckRepository checks = mock(RetaliationCheckRepository.class);
    private final EthicsService ethics = mock(EthicsService.class);
    private final RetaliationCheckDispatcher dispatcher = new RetaliationCheckDispatcher(checks, ethics);

    private RetaliationCheck check(short months, Instant dueAt) {
        return new RetaliationCheck(UUID.randomUUID(), UUID.randomUUID(), ORG, months, dueAt);
    }

    @Test
    void aDueCheckIsPutToTheReporterAndStamped() {
        RetaliationCheck due = check((short) 3, NOW.minusSeconds(60));
        when(checks.findAll()).thenReturn(List.of(due));

        dispatcher.dispatchDueAt(NOW);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(ethics).systemReply(eq(ORG), eq(due.getCaseId()), any(), body.capture(), any());
        // The message names the forms rather than asking whether "anything bad" happened: a
        // person who has not connected their transfer to their report answers no to the
        // vague question and yes to the specific one.
        assertTrue(body.getValue().contains("görev ya da yer"));
        assertTrue(body.getValue().contains("Kimliğinizi paylaşmanız gerekmiyor"));
        // And it says this is routine, because a message months after closure with no
        // explanation reads as the case being reopened.
        assertTrue(body.getValue().contains("dosyanız yeniden açılmadı"));
        assertNotNull(due.getAskedAt());
        assertEquals(NOW, due.getAskedAt());
    }

    @Test
    void aCheckThatIsNotYetDueIsLeftAlone() {
        RetaliationCheck future = check((short) 6, NOW.plusSeconds(86_400));
        when(checks.findAll()).thenReturn(List.of(future));

        dispatcher.dispatchDueAt(NOW);

        verify(ethics, never()).systemReply(any(), any(), any(), any(), any());
        assertNull(future.getAskedAt());
    }

    @Test
    void anAlreadyAskedCheckIsNotAskedAgain() {
        RetaliationCheck asked = check((short) 3, NOW.minusSeconds(60));
        asked.markAsked(NOW.minusSeconds(30));
        when(checks.findAll()).thenReturn(List.of(asked));

        dispatcher.dispatchDueAt(NOW);

        // Asking twice reads, to the reporter, as the organisation having lost track of
        // their case — which is the opposite of the reassurance the message exists to give.
        verify(ethics, never()).systemReply(any(), any(), any(), any(), any());
    }

    @Test
    void oneFailingCaseDoesNotStopTheRest() {
        RetaliationCheck broken = check((short) 3, NOW.minusSeconds(60));
        RetaliationCheck healthy = check((short) 6, NOW.minusSeconds(60));
        when(checks.findAll()).thenReturn(List.of(broken, healthy));
        when(ethics.systemReply(eq(ORG), eq(broken.getCaseId()), any(), any(), any()))
                .thenThrow(new IllegalStateException("mailbox write failed"));

        dispatcher.dispatchDueAt(NOW);

        // A single batch transaction would have let one deleted case silence every other
        // reporter due that hour. The broken one stays unasked and stays in the overdue
        // count, which is the honest outcome.
        verify(ethics, times(1)).systemReply(eq(ORG), eq(healthy.getCaseId()), any(), any(), any());
        assertNotNull(healthy.getAskedAt());
        assertNull(broken.getAskedAt());
    }

    @Test
    void theIdempotencyKeyIsTheCheckSoARetryCannotDoubleAsk() {
        RetaliationCheck due = check((short) 12, NOW.minusSeconds(60));
        when(checks.findAll()).thenReturn(List.of(due));

        dispatcher.dispatchDueAt(NOW);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(ethics).systemReply(any(), any(), key.capture(), any(), any());
        assertEquals("retaliation-check-" + due.getId(), key.getValue());
    }
}
