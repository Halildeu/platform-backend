package com.example.meeting.service;

import java.util.Optional;

/**
 * Resolves a public-directory numeric user id to the stable KC subject.
 * Faz 24 Görevler (gitops#3507); see {@link
 * com.example.meeting.config.MeetingAssigneeDirectoryProperties} for why
 * resolution is server-side.
 */
public interface AssigneeDirectoryClient {

    /**
     * @return the non-blank KC subject for an existing, enabled user; empty
     *     when the user does not exist or carries no subject binding yet.
     * @throws ResolutionUnavailableException when the directory cannot be
     *     consulted at all (disabled, network, token failure) — callers must
     *     fail closed rather than silently dropping the assignment.
     */
    Optional<String> resolveKcSubject(long userId);

    /**
     * Reverse lookup for notifications (Faz 24 Görevler dilim-4b): the platform
     * numeric user id behind a Keycloak subject, via user-service's canonical
     * {@code POST /api/users/internal/authenticated-principal/resolve}. Empty when the
     * subject is unknown to the directory. Default (test doubles, lambdas): unknown.
     */
    default Optional<Long> resolveUserId(String issuer, String kcSubject) {
        return Optional.empty();
    }

    class ResolutionUnavailableException extends RuntimeException {
        public ResolutionUnavailableException(String message) {
            super(message);
        }
    }
}
