package com.example.audiogateway.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * Validates that the caller can see the canonical meeting before audio capture starts.
 */
public interface MeetingAccessValidator {

    Mono<Decision> validate(String meetingId, Jwt jwt, String correlationId);

    record Decision(
            boolean allowed,
            HttpStatus status,
            String message,
            boolean retryable,
            UUID tenantId,
            UUID orgId) {

        public static Decision granted() {
            return new Decision(true, HttpStatus.OK, "allowed", false, null, null);
        }

        public static Decision granted(final UUID tenantId, final UUID orgId) {
            return new Decision(true, HttpStatus.OK, "allowed", false, tenantId, orgId);
        }

        public static Decision forbidden(final String message) {
            return new Decision(false, HttpStatus.FORBIDDEN, message, false, null, null);
        }

        public static Decision unavailable(final String message) {
            return new Decision(false, HttpStatus.SERVICE_UNAVAILABLE, message, true, null, null);
        }
    }
}
