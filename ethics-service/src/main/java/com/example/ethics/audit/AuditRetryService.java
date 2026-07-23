package com.example.ethics.audit;

import com.example.ethics.config.AuditDeliveryProperties;
import com.example.ethics.repository.AuditOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists retry/DLQ state in a fresh transaction after a delivery rollback. */
@Service
public class AuditRetryService {
    private final AuditOutboxRepository outbox;
    private final AuditDeliveryProperties properties;

    public AuditRetryService(AuditOutboxRepository outbox, AuditDeliveryProperties properties) {
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
        if (attemptCount >= properties.maxAttempts()) {
            int updated = outbox.markDeadLetter(id, claimToken, lockedUntil, errorCode);
            requireSingleUpdate(updated);
            return RetryResult.DEAD_LETTER;
        }

        Instant nextAttempt = now.plus(backoff(attemptCount));
        int updated = outbox.markRetry(
                id, claimToken, lockedUntil, nextAttempt, errorCode);
        requireSingleUpdate(updated);
        return RetryResult.RETRY_SCHEDULED;
    }

    Duration backoff(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 30));
        long multiplier = 1L << exponent;
        Duration candidate;
        try {
            candidate = properties.retryBaseDelay().multipliedBy(multiplier);
        } catch (ArithmeticException error) {
            candidate = properties.retryMaxDelay();
        }
        return candidate.compareTo(properties.retryMaxDelay()) > 0
                ? properties.retryMaxDelay()
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
            throw new IllegalStateException("Audit retry/DLQ CAS fence rejected stale worker");
        }
    }

    public enum RetryResult {
        RETRY_SCHEDULED,
        DEAD_LETTER
    }
}
