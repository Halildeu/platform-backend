package com.example.user.config;

import com.example.commonauth.AuthorizationContextCache;
import com.example.user.dto.ApiErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status a browser actually receives when the authorization revision cannot be established.
 *
 * <p>The refusal itself is correct and stays: an unknown revision is never read as "unchanged",
 * which is how a revoked grant used to survive its whole TTL. What was missing was any mapping —
 * {@code RevisionUnavailableException} had no handler, so it surfaced as an opaque <b>500</b>.
 *
 * <p>Measured on the live cell 2026-07-27: an operator whose session token was rejected saw
 * "could not retrieve user data, check your connection" on {@code /admin/users} and read it as lost
 * authorization. Their roles were intact and a fresh token returned 200 from the same endpoint — the
 * state was recoverable and the response said otherwise, so nothing attempted a refresh.
 *
 * <p>These assert the two outcomes separately because they ask the client for different things:
 * 401 means "revalidate this session", 503 means "the platform cannot answer right now".
 */
class RestExceptionHandlerRevisionMappingTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    @Test
    @DisplayName("rejected caller credential is relayed as 401 so the session can be refreshed")
    void rejected_credential_maps_to_401() {
        ResponseEntity<ApiErrorResponse> res = handler.handleRevisionUnavailable(
                new AuthorizationContextCache.RevisionUnavailableException(
                        "authorization revision unavailable; refusing to reuse a cached grant",
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ERR_SESSION_REVALIDATION_REQUIRED")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().errorCode()).isEqualTo("ERR_SESSION_REVALIDATION_REQUIRED");
    }

    @Test
    @DisplayName("an unreachable permission-service stays fail-closed as 503")
    void platform_failure_maps_to_503() {
        ResponseEntity<ApiErrorResponse> res = handler.handleRevisionUnavailable(
                new AuthorizationContextCache.RevisionUnavailableException(
                        "authorization revision unavailable; refusing to reuse a cached grant",
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ERR_AUTHZ_REVISION_UNAVAILABLE")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody().errorCode()).isEqualTo("ERR_AUTHZ_REVISION_UNAVAILABLE");
    }

    /**
     * A cause that carries no status must NOT silently become 200 or 500 — it is still an unknown
     * revision, so it defaults to the fail-closed 503.
     */
    @Test
    @DisplayName("a cause without a status defaults to fail-closed 503, never 500")
    void unknown_cause_defaults_to_503() {
        ResponseEntity<ApiErrorResponse> res = handler.handleRevisionUnavailable(
                new AuthorizationContextCache.RevisionUnavailableException("no context", new IllegalStateException("boom")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getStatusCode().value()).isNotEqualTo(500);
    }
}
