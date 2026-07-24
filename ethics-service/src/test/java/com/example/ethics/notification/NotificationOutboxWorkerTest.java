package com.example.ethics.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.model.NotificationOutbox;
import com.example.ethics.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationOutboxWorkerTest {

    @Test
    void providerOutageSchedulesRetryWithoutCallingDeliveredCheckpoint() {
        NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
        NotificationIntentGateway gateway = mock(NotificationIntentGateway.class);
        NotificationCheckpointService checkpoint = mock(NotificationCheckpointService.class);
        NotificationRetryService retry = mock(NotificationRetryService.class);
        TransactionTemplate transactions = immediateTransactions();
        var row = rowWithAttempt(1);
        when(outbox.recoverExpiredLeases(any())).thenReturn(0);
        when(outbox.claimDue(any(), any(), any(), anyInt())).thenReturn(1);
        when(outbox.findByClaimTokenOrderByCreatedAtAsc(any())).thenReturn(List.of(row));
        doThrow(new IllegalStateException("synthetic provider outage"))
                .when(gateway).submit(row);
        when(retry.recordFailure(
                        eq(row.getId()), any(), any(), eq(1), any(), any()))
                .thenReturn(NotificationRetryService.RetryResult.RETRY_SCHEDULED);

        var result = worker(outbox, gateway, checkpoint, retry, transactions).runCycle();

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.delivered()).isZero();
        assertThat(result.retryScheduled()).isEqualTo(1);
        assertThat(result.deadLettered()).isZero();
        verify(checkpoint, never()).markDelivered(any(), any(), any(), any());
    }

    @Test
    void providerAcceptanceUsesCasCheckpoint() {
        NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
        NotificationIntentGateway gateway = mock(NotificationIntentGateway.class);
        NotificationCheckpointService checkpoint = mock(NotificationCheckpointService.class);
        NotificationRetryService retry = mock(NotificationRetryService.class);
        TransactionTemplate transactions = immediateTransactions();
        var row = rowWithAttempt(1);
        when(outbox.recoverExpiredLeases(any())).thenReturn(0);
        when(outbox.claimDue(any(), any(), any(), anyInt())).thenReturn(1);
        when(outbox.findByClaimTokenOrderByCreatedAtAsc(any())).thenReturn(List.of(row));

        var result = worker(outbox, gateway, checkpoint, retry, transactions).runCycle();

        assertThat(result.delivered()).isEqualTo(1);
        assertThat(result.retryScheduled()).isZero();
        verify(gateway).submit(row);
        verify(checkpoint).markDelivered(eq(row.getId()), any(), any(), any());
        verify(retry, never()).recordFailure(any(), any(), any(), anyInt(), any(), any());
    }

    private static NotificationOutboxWorker worker(
            NotificationOutboxRepository outbox,
            NotificationIntentGateway gateway,
            NotificationCheckpointService checkpoint,
            NotificationRetryService retry,
            TransactionTemplate transactions) {
        var properties = new NotificationDeliveryProperties();
        properties.setEnabled(true);
        properties.setClientSecret("synthetic-test-only-secret");
        properties.setRecipientSubscriberId("ethics-triage");
        properties.setLeaseDuration(Duration.ofMinutes(1));
        properties.setBatchSize(10);
        return new NotificationOutboxWorker(
                outbox,
                gateway,
                checkpoint,
                retry,
                properties,
                transactions,
                new SimpleMeterRegistry());
    }

    private static NotificationOutbox rowWithAttempt(int attempt) {
        var row = new NotificationOutbox(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationOutboxPublisher.NEW_REPORT,
                Instant.parse("2026-07-24T00:00:00Z"));
        ReflectionTestUtils.setField(row, "attemptCount", attempt);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        return transactions;
    }
}
