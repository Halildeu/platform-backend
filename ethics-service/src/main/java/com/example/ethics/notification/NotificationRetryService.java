package com.example.ethics.notification;

import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.repository.NotificationOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists bounded retry/DLQ state after a provider or checkpoint failure. */
@Service
public class NotificationRetryService {
    private final NotificationOutboxRepository outbox;
    private final NotificationDeliveryProperties properties;

    public NotificationRetryService(
            NotificationOutboxRepository outbox,
            NotificationDeliveryProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetryResult recordFailure(
            UUID id,
            UUID claimToken,
            Instant lockedUntil,
            int attemptCount,
            Instant now,
            Throwable error) {
        String errorCode = boundedErrorCode(error);
        if (attemptCount >= properties.getMaxAttempts()) {
            requireSingleUpdate(outbox.markDeadLetter(
                    id, claimToken, lockedUntil, errorCode));
            return RetryResult.DEAD_LETTER;
        }

        requireSingleUpdate(outbox.markRetry(
                id,
                claimToken,
                lockedUntil,
                now.plus(backoff(attemptCount)),
                errorCode));
        return RetryResult.RETRY_SCHEDULED;
    }

    Duration backoff(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 30));
        long multiplier = 1L << exponent;
        Duration candidate;
        try {
            candidate = properties.getRetryBaseDelay().multipliedBy(multiplier);
        } catch (ArithmeticException error) {
            candidate = properties.getRetryMaxDelay();
        }
        return candidate.compareTo(properties.getRetryMaxDelay()) > 0
                ? properties.getRetryMaxDelay()
                : candidate;
    }

    private static String boundedErrorCode(Throwable error) {
        String value = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
        if (value == null || value.isBlank()) {
            value = "RUNTIME_ERROR";
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static void requireSingleUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Notification retry/DLQ CAS fence rejected stale worker");
        }
    }

    public enum RetryResult {
        RETRY_SCHEDULED,
        DEAD_LETTER
    }
}
