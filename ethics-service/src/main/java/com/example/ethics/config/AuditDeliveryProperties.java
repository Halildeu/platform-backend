package com.example.ethics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded operational controls for the product-local audit outbox worker. */
@ConfigurationProperties(prefix = "ethics.audit-delivery")
public record AuditDeliveryProperties(
        boolean enabled,
        Duration pollDelay,
        int batchSize,
        Duration leaseDuration,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        int maxAttempts,
        String owner) {

    public AuditDeliveryProperties {
        if (pollDelay == null || pollDelay.isNegative() || pollDelay.isZero()) {
            pollDelay = Duration.ofSeconds(5);
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("ethics.audit-delivery.batch-size must be 1..500");
        }
        if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(5)) < 0) {
            throw new IllegalArgumentException("ethics.audit-delivery.lease-duration must be >= 5s");
        }
        if (retryBaseDelay == null || retryBaseDelay.isNegative() || retryBaseDelay.isZero()) {
            throw new IllegalArgumentException("ethics.audit-delivery.retry-base-delay must be positive");
        }
        if (retryMaxDelay == null || retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException(
                    "ethics.audit-delivery.retry-max-delay must be >= retry-base-delay");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("ethics.audit-delivery.max-attempts must be 1..100");
        }
        if (owner == null || owner.isBlank() || owner.length() > 120) {
            throw new IllegalArgumentException("ethics.audit-delivery.owner is required and <= 120 chars");
        }
    }
}
