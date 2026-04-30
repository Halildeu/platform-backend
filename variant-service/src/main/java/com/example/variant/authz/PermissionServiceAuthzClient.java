package com.example.variant.authz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class PermissionServiceAuthzClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceAuthzClient.class);

    private final WebClient webClient;

    @Autowired
    public PermissionServiceAuthzClient(@Qualifier("plainWebClientBuilder") WebClient.Builder plainWebClientBuilder,
                                        @Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        // D7: @LoadBalanced kaldırıldı — K8s DNS ile plain builder yeterli.
        this.webClient = plainWebClientBuilder.baseUrl(baseUrl).build();
    }

    public PermissionServiceAuthzClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://permission-service").build();
    }

    /**
     * Codex 019dddb7 iter-42 — typed-error contract.
     *
     * <p>Pre-iter-42 every failure path produced a fresh empty
     * {@code AuthzMeResponse}. Callers (the variant controllers) then
     * inspected {@code userId == null} and threw HTTP 401, conflating an
     * upstream outage with a client authentication failure. The
     * frontend's shared-http listener treats every 401 as a session
     * expiry, so a transient permission-service blip would log users out.
     *
     * <p>Now we surface the failure mode explicitly:
     * <ul>
     *   <li>{@link AuthzDependencyUnavailableException} (→ 503): network
     *   error, timeout, or upstream 5xx.</li>
     *   <li>{@link AuthzUpstreamInvalidResponseException} (→ 502): 2xx
     *   response body that's empty, malformed, or missing the userId
     *   field that the variant module relies on. The legacy "200 + empty
     *   body" race that iter-34 worked around at the consumer is now
     *   detected at the authz client.</li>
     *   <li>4xx upstream responses propagate as
     *   {@link AuthzDependencyUnavailableException} for the same reason
     *   — permission-service should never reject a token that survived
     *   our own oauth2ResourceServer filter; if it does, the contract is
     *   broken, not the user's session.</li>
     * </ul>
     */
    public AuthzMeResponse getAuthzMe(String bearerToken) {
        try {
            WebClient.RequestHeadersSpec<?> request = webClient.get()
                    .uri("/api/v1/authz/me");
            if (bearerToken != null && !bearerToken.isBlank()) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            }
            AuthzMeResponse response = request.retrieve()
                    .bodyToMono(AuthzMeResponse.class)
                    .block();
            if (response == null) {
                throw new AuthzUpstreamInvalidResponseException(
                        "permission-service /authz/me returned no body");
            }
            if (response.getUserId() == null || response.getUserId().isBlank()) {
                throw new AuthzUpstreamInvalidResponseException(
                        "permission-service /authz/me response missing userId");
            }
            return response;
        } catch (WebClientResponseException ex) {
            // permission-service responded with an HTTP error. Whatever the
            // class (4xx or 5xx) the variant caller's JWT was already
            // accepted by our own resource-server filter; the upstream
            // failure is not the caller's authentication problem.
            log.warn("permission-service /authz/me returned {}: {}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new AuthzDependencyUnavailableException(
                    "permission-service /authz/me returned " + ex.getStatusCode(), ex);
        } catch (AuthzUpstreamInvalidResponseException
                | AuthzDependencyUnavailableException
                | AuthzIdentityResolutionException ex) {
            // Already-typed authz failures are propagated as-is so the
            // RestExceptionHandler maps them to the right HTTP status.
            throw ex;
        } catch (RuntimeException ex) {
            // Network errors, decode failures, anything else.
            log.warn("permission-service /authz/me failed", ex);
            throw new AuthzDependencyUnavailableException(
                    "permission-service /authz/me unreachable: " + ex.getMessage(), ex);
        }
    }

}
