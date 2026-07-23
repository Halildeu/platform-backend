package com.example.ethics.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.config.AuditDeliveryProperties;
import com.example.ethics.repository.AuditOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditRetryServiceTest {
    @Mock AuditOutboxRepository repository;

    private AuditDeliveryProperties properties(int maxAttempts) {
        return new AuditDeliveryProperties(
                true,
                Duration.ofSeconds(5),
                20,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                maxAttempts,
                "test-worker");
    }

    @Test
    void retryUsesBoundedExponentialBackoffWithoutPersistingErrorMessage() {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Instant lock = Instant.parse("2026-07-24T00:01:00Z");
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        when(repository.markRetry(
                id, token, lock, now.plusSeconds(20), "IllegalArgumentException"))
                .thenReturn(1);

        AuditRetryService service = new AuditRetryService(repository, properties(8));
        var result = service.recordFailure(
                id, token, lock, 3, now,
                new IllegalArgumentException("must not be persisted"));

        assertThat(result).isEqualTo(AuditRetryService.RetryResult.RETRY_SCHEDULED);
        verify(repository).markRetry(
                id, token, lock, now.plusSeconds(20), "IllegalArgumentException");
    }

    @Test
    void exhaustedAttemptsEnterDurableDeadLetterState() {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Instant lock = Instant.parse("2026-07-24T00:01:00Z");
        when(repository.markDeadLetter(id, token, lock, "IllegalStateException"))
                .thenReturn(1);

        AuditRetryService service = new AuditRetryService(repository, properties(3));
        var result = service.recordFailure(
                id, token, lock, 3, Instant.parse("2026-07-24T00:00:00Z"),
                new IllegalStateException("payload-free operator code"));

        assertThat(result).isEqualTo(AuditRetryService.RetryResult.DEAD_LETTER);
        verify(repository).markDeadLetter(id, token, lock, "IllegalStateException");
    }
}
