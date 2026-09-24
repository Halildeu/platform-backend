package com.example.meeting.service;

import java.util.List;
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

    /**
     * People-picker search on behalf of the signed-in user (gitops#3834), via user-service's
     * {@code POST /api/users/internal/assignee-candidates}. user-service scopes the answer by the
     * REQUESTER's directory row (global users + the requester's own company), so the caller passes
     * the requester's Keycloak subject — never a subject of its own choosing.
     *
     * @throws DirectoryAccessDeniedException when the requester is not an active directory member
     * @throws ResolutionUnavailableException when the directory cannot be consulted; callers must
     *     surface it, never turn it into an empty list (an empty picker reads as "nobody matches")
     */
    default List<AssigneeCandidate> searchCandidates(String requesterSubject, String query, int limit) {
        throw new ResolutionUnavailableException("assignee candidate search not supported");
    }

    /** One assignable person as the directory reports it. */
    record AssigneeCandidate(long userId, String name, String email) {
    }

    /** The directory knows the requester is not an active member — a deny, not an outage. */
    class DirectoryAccessDeniedException extends RuntimeException {
        public DirectoryAccessDeniedException(String message) {
            super(message);
        }
    }

    class ResolutionUnavailableException extends RuntimeException {
        public ResolutionUnavailableException(String message) {
            super(message);
        }
    }
}
