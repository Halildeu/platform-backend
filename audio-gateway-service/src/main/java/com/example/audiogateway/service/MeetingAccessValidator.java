package com.example.audiogateway.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * Validates that the caller can see the canonical meeting before audio capture starts.
 */
public interface MeetingAccessValidator {

    Mono<Decision> validate(String meetingId, Jwt jwt, String correlationId);

    /**
     * Outcome of the object-level meeting check.
     *
     * <p>{@code speechContextTerms} carries the consent-bound vocabulary the meeting owner
     * configured on the canonical meeting contract (platform-backend#1024). It is never
     * null: empty when the meeting has no vocabulary, when the decision is not a grant, or
     * when the meeting-service response predates the field (mixed-version rollout).
     */
    record Decision(
            boolean allowed,
            HttpStatus status,
            String message,
            boolean retryable,
            UUID tenantId,
            UUID orgId,
            List<String> speechContextTerms) {

        public Decision {
            speechContextTerms = speechContextTerms == null
                    ? List.of()
                    : speechContextTerms.stream().filter(Objects::nonNull).toList();
        }

        public static Decision granted() {
            return granted(null, null, List.of());
        }

        public static Decision granted(final UUID tenantId, final UUID orgId) {
            return granted(tenantId, orgId, List.of());
        }

        public static Decision granted(
                final UUID tenantId,
                final UUID orgId,
                final List<String> speechContextTerms) {
            return new Decision(
                    true, HttpStatus.OK, "allowed", false, tenantId, orgId, speechContextTerms);
        }

        public static Decision forbidden(final String message) {
            return new Decision(
                    false, HttpStatus.FORBIDDEN, message, false, null, null, List.of());
        }

        public static Decision unavailable(final String message) {
            return new Decision(
                    false, HttpStatus.SERVICE_UNAVAILABLE, message, true, null, null, List.of());
        }
    }
}
