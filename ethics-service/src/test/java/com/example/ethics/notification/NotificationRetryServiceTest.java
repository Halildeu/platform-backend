package com.example.ethics.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.repository.NotificationOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRetryServiceTest {

    @Test
    void retryUsesBoundedBackoffAndPersistsOnlyErrorClass() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationDeliveryProperties properties = properties(8);
        NotificationRetryService service =
                new NotificationRetryService(repository, properties);
        UUID id = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        Instant locked = Instant.parse("2026-07-24T00:01:00Z");
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        when(repository.markRetry(
                        eq(id),
                        eq(claim),
                        eq(locked),
                        eq(now.plusSeconds(40)),
                        eq("IllegalStateException")))
                .thenReturn(1);

        var result = service.recordFailure(
                id,
                claim,
                locked,
                3,
                now,
                new IllegalStateException("provider response may contain sensitive text"));

        assertThat(result).isEqualTo(
                NotificationRetryService.RetryResult.RETRY_SCHEDULED);
        verify(repository).markRetry(
                id, claim, locked, now.plusSeconds(40), "IllegalStateException");
    }

    @Test
    void maxAttemptTransitionsToDurableDeadLetter() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationDeliveryProperties properties = properties(3);
        NotificationRetryService service =
                new NotificationRetryService(repository, properties);
        UUID id = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        Instant locked = Instant.parse("2026-07-24T00:01:00Z");
        when(repository.markDeadLetter(
                        id, claim, locked, "IllegalArgumentException"))
                .thenReturn(1);

        var result = service.recordFailure(
                id,
                claim,
                locked,
                3,
                Instant.parse("2026-07-24T00:00:00Z"),
                new IllegalArgumentException("raw provider body is not persisted"));

        assertThat(result).isEqualTo(
                NotificationRetryService.RetryResult.DEAD_LETTER);
        verify(repository).markDeadLetter(
                id, claim, locked, "IllegalArgumentException");
    }

    private static NotificationDeliveryProperties properties(int maxAttempts) {
        var properties = new NotificationDeliveryProperties();
        properties.setRetryBaseDelay(Duration.ofSeconds(10));
        properties.setRetryMaxDelay(Duration.ofMinutes(1));
        properties.setMaxAttempts(maxAttempts);
        return properties;
    }
}
