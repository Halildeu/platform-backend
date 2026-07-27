package com.example.user.authz;

import com.example.commonauth.AuthorizationContext;
import com.example.commonauth.AuthorizationContextCache;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthorizationContextServiceTest {

    private static final AtomicLong REVISION = new AtomicLong(1);

    private static Jwt jwt() {
        return Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .subject("admin@example.com")
                .claim("iss", "https://testai.acik.com/realms/platform-test")
                .claim("email", "admin@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    /**
     * board #2556: the service now asks permission-service two different questions — the cheap
     * revision on every request, and the expensive /authz/me only when it moved. A stub that answers
     * both with the same body would not be exercising the real contract.
     */
    private static ExchangeFunction upstream(AtomicLong revision, java.util.function.Supplier<String> authzMeBody,
                                             AtomicInteger authzMeCalls) {
        return request -> {
            if (request.url().getPath().endsWith("/api/v1/authz/version")) {
                return Mono.just(json("{\"authzVersion\":" + revision.get() + "}"));
            }
            authzMeCalls.incrementAndGet();
            return Mono.just(json(authzMeBody.get()));
        };
    }

    private static AuthorizationContextService service(ExchangeFunction exchange) {
        return new AuthorizationContextService(
                WebClient.builder().exchangeFunction(exchange),
                new AuthorizationContextCache(Duration.ofMinutes(1)),
                "http://permission-service");
    }

    /**
     * permission-service authenticates `/api/v1/**`, so a revision call without a credential is
     * answered 401 — and user-service reports that two layers up as an opaque 500 on
     * `/api/v1/users`, which is how listing and creating users broke cell-wide while every pod
     * stayed green.
     *
     * <p>The credential must be the caller's own bearer token — the same one `/authz/me` already
     * carries. An earlier fix sent `X-Internal-Api-Key`; measured against the running cell that is
     * answered 401 exactly like a bare call, because the filter reading that header is
     * profile-scoped to local/dev and matches only `/api/v1/internal/**`. Asserting the *bearer*
     * here is what makes this test able to fail on the wrong credential, which the header-only
     * assertion could not.
     */
    @Test
    void authz_version_call_should_carry_the_callers_bearer_token() {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        ExchangeFunction exchange = request -> {
            if (request.url().getPath().endsWith("/api/v1/authz/version")) {
                seen.set(request.headers().getFirst("Authorization"));
                return Mono.just(json("{\"authzVersion\":1}"));
            }
            return Mono.just(json("{\"userId\":\"1\",\"permissions\":[],\"allowedScopes\":[]}"));
        };

        service(exchange).buildContext(jwt(), java.util.List.of());

        org.assertj.core.api.Assertions.assertThat(seen.get()).isEqualTo("Bearer token-value");
    }

    /**
     * The revision call and the projection call must present the *same* identity. If they diverge,
     * one of them is authenticating as somebody else — and since the revision decides whether a
     * cached grant may be reused, that divergence is a way for a stale grant to survive under a
     * label that looks fresh.
     */
    @Test
    void authz_version_and_authz_me_should_present_the_same_credential() {
        java.util.concurrent.atomic.AtomicReference<String> versionAuth = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> meAuth = new java.util.concurrent.atomic.AtomicReference<>();
        ExchangeFunction exchange = request -> {
            if (request.url().getPath().endsWith("/api/v1/authz/version")) {
                versionAuth.set(request.headers().getFirst("Authorization"));
                return Mono.just(json("{\"authzVersion\":1}"));
            }
            meAuth.set(request.headers().getFirst("Authorization"));
            return Mono.just(json("{\"userId\":\"1\",\"permissions\":[],\"allowedScopes\":[]}"));
        };

        service(exchange).buildContext(jwt(), java.util.List.of());

        org.assertj.core.api.Assertions.assertThat(versionAuth.get()).isEqualTo(meAuth.get());
    }

    @Test
    void buildContext_should_expand_legacy_permissions_from_authz_me_response() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(upstream(new AtomicLong(1), () -> """
                {
                  "userId": "1",
                  "permissions": ["VIEW_USERS", "MANAGE_USERS"],
                  "allowedScopes": [],
                  "superAdmin": true
                }
                """, calls));

        AuthorizationContext context = service.buildContext(jwt(), Collections.emptyList());

        assertThat(context.getUserId()).isEqualTo(1L);
        assertThat(context.hasPermission("user-read")).isTrue();
        assertThat(context.hasPermission("user-create")).isTrue();
        assertThat(context.hasPermission("user-update")).isTrue();
        assertThat(context.hasPermission("user-delete")).isTrue();
        assertThat(context.hasPermission("user-export")).isTrue();
        assertThat(context.hasPermission("user-import")).isTrue();
    }

    @Test
    @DisplayName("revoke is visible on the very next request — no TTL wait (board #2556)")
    void revokeIsVisibleImmediately() {
        AtomicLong revision = new AtomicLong(127);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger phase = new AtomicInteger();
        var service = service(upstream(revision,
                () -> phase.get() == 0
                        ? "{\"userId\":\"1\",\"permissions\":[\"VIEW_USERS\"],\"allowedScopes\":[],\"superAdmin\":false}"
                        : "{\"userId\":\"1\",\"permissions\":[],\"allowedScopes\":[],\"superAdmin\":false}",
                calls));

        Jwt token = jwt();
        assertThat(service.buildContext(token, Collections.emptyList()).hasPermission("user-read")).isTrue();

        // An admin revokes: /authz/me stops granting and the outbox bumps the revision.
        phase.set(1);
        revision.set(129);

        AuthorizationContext after = service.buildContext(token, Collections.emptyList());

        assertThat(after.grantsNothing())
                .as("the same token must lose the grant at once; the old TTL-only cache held it for minutes")
                .isTrue();
    }

    @Test
    @DisplayName("unchanged revision is served from cache — /authz/me is not re-asked per request")
    void unchangedRevisionIsCached() {
        AtomicInteger authzMeCalls = new AtomicInteger();
        var service = service(upstream(new AtomicLong(5),
                () -> "{\"userId\":\"1\",\"permissions\":[\"VIEW_USERS\"],\"allowedScopes\":[],\"superAdmin\":false}",
                authzMeCalls));

        Jwt token = jwt();
        service.buildContext(token, Collections.emptyList());
        service.buildContext(token, Collections.emptyList());
        service.buildContext(token, Collections.emptyList());

        assertThat(authzMeCalls).as("while the revision holds, the expensive call happens once").hasValue(1);
    }

    @Test
    @DisplayName("a fresh grant is visible at once too — a cached deny does not outlive it")
    void grantIsVisibleImmediately() {
        AtomicLong revision = new AtomicLong(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger phase = new AtomicInteger();
        var service = service(upstream(revision,
                () -> phase.get() == 0
                        ? "{\"userId\":\"1\",\"permissions\":[],\"allowedScopes\":[],\"superAdmin\":false}"
                        : "{\"userId\":\"1\",\"permissions\":[\"VIEW_USERS\"],\"allowedScopes\":[],\"superAdmin\":false}",
                calls));

        Jwt token = jwt();
        assertThat(service.buildContext(token, Collections.emptyList()).grantsNothing()).isTrue();

        phase.set(1);
        revision.set(2);

        assertThat(service.buildContext(token, Collections.emptyList()).hasPermission("user-read")).isTrue();
    }

    @Test
    @DisplayName("SECURITY: revision unreadable → a cached grant is refused, not reused")
    void unreadableRevisionRefusesCachedGrant() {
        AtomicBooleanish versionDown = new AtomicBooleanish();
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            if (request.url().getPath().endsWith("/api/v1/authz/version")) {
                if (versionDown.value) {
                    return Mono.error(new IllegalStateException("permission-service down"));
                }
                return Mono.just(json("{\"authzVersion\":1}"));
            }
            calls.incrementAndGet();
            return Mono.just(json(
                    "{\"userId\":\"1\",\"permissions\":[\"VIEW_USERS\"],\"allowedScopes\":[],\"superAdmin\":false}"));
        };
        var service = service(exchange);

        Jwt token = jwt();
        assertThat(service.buildContext(token, Collections.emptyList()).hasPermission("user-read")).isTrue();

        versionDown.value = true;

        assertThatThrownBy(() -> service.buildContext(token, Collections.emptyList()))
                .as("an outage must not promote a stale allow into an answer")
                .isInstanceOf(AuthorizationContextCache.RevisionUnavailableException.class);
    }

    /** Tiny mutable flag — a plain boolean cannot be captured by the lambda above. */
    private static final class AtomicBooleanish {
        volatile boolean value;
    }

    @Test
    @DisplayName("SECURITY: /authz/me unreachable → fail closed; authority is never rebuilt from the token")
    void authzMeUnreachableFailsClosed() {
        ExchangeFunction exchange = request -> {
            if (request.url().getPath().endsWith("/api/v1/authz/version")) {
                return Mono.just(json("{\"authzVersion\":1}"));
            }
            return Mono.error(new IllegalStateException("permission-service down"));
        };
        var service = service(exchange);

        // A token carrying rich claims — the old fallback would have handed these back as authority.
        Jwt loaded = Jwt.withTokenValue("t").header("alg", "none")
                .subject("admin@example.com")
                .claim("iss", "https://testai.acik.com/realms/platform-test")
                .claim("email", "admin@example.com")
                .claim("permissions", java.util.List.of("VIEW_USERS", "MANAGE_USERS"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThatThrownBy(() -> service.buildContext(loaded, Collections.emptyList()))
                .as("an unreachable authority must not degrade into 'trust the token'")
                .isInstanceOf(AuthorizationContextCache.RevisionUnavailableException.class);
    }

    @Test
    @DisplayName("SECURITY: /authz/me returns no body → fail closed (a non-answer is not a deny)")
    void authzMeNullBodyFailsClosed() {
        ExchangeFunction exchange = request -> {
            if (request.url().getPath().endsWith("/api/v1/authz/version")) {
                return Mono.just(json("{\"authzVersion\":1}"));
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build());   // 2xx, empty body
        };

        assertThatThrownBy(() -> service(exchange).buildContext(jwt(), Collections.emptyList()))
                .isInstanceOf(AuthorizationContextCache.RevisionUnavailableException.class);
    }

    /** Same principal, but the token has genuinely lapsed — that is what justifies a 401. */
    private static Jwt expiredJwt() {
        return Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .subject("admin@example.com")
                .claim("iss", "https://testai.acik.com/realms/platform-test")
                .claim("email", "admin@example.com")
                .issuedAt(Instant.now().minusSeconds(3600))
                .expiresAt(Instant.now().minusSeconds(120))
                .build();
    }

    private static ClientResponse status(HttpStatus code) {
        return ClientResponse.create(code)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{}")
                .build();
    }

    private static ExchangeFunction revisionAnswers(HttpStatus code) {
        return request -> request.url().getPath().endsWith("/api/v1/authz/version")
                ? Mono.just(status(code))
                : Mono.just(json("{\"userId\":\"1\",\"permissions\":[],\"allowedScopes\":[]}"));
    }

    /**
     * The revision call relays the CALLER's bearer token, so permission-service rejecting it is a
     * statement about this session — an expired token, most often — not about the platform. It has
     * to reach the browser as 401, the one status a client can recover from by refreshing.
     *
     * <p>Measured on the live cell 2026-07-27: this path had no catch at all, so the 401 fell
     * through to the generic handler as 500 and `/admin/users` rendered "could not retrieve user
     * data, check your connection". The operator read that as lost authorization and nothing
     * attempted a refresh, even though their roles were intact and a fresh token worked.
     */
    @Test
    @DisplayName("rejected caller credential surfaces as 401, not an opaque 500")
    void revision_401_with_expired_token_should_surface_as_401() {
        assertThatThrownBy(() -> service(revisionAnswers(HttpStatus.UNAUTHORIZED)).buildContext(expiredJwt(), java.util.List.of()))
                .isInstanceOf(AuthorizationContextCache.RevisionUnavailableException.class)
                .cause()
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(c -> {
                    org.springframework.web.server.ResponseStatusException rse =
                            (org.springframework.web.server.ResponseStatusException) c;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).isEqualTo("ERR_SESSION_REVALIDATION_REQUIRED");
                });
    }

    /**
     * The protection variant-service paid for in iter-42: permission-service can reject a perfectly
     * good token for its own reasons — key rotation, clock skew, a bad issuer. Answering 401 there
     * would cost every user their session, because the frontend's shared-http listener escalates any
     * 401 into a global logout. A still-valid token therefore yields 503, not 401.
     */
    @Test
    @DisplayName("upstream rejects a STILL-VALID token: 503, so one blip cannot log everyone out")
    void revision_401_with_valid_token_should_not_expire_the_session() {
        assertThatThrownBy(() -> service(revisionAnswers(HttpStatus.UNAUTHORIZED)).buildContext(jwt(), java.util.List.of()))
                .isInstanceOf(AuthorizationContextCache.RevisionUnavailableException.class)
                .cause()
                .extracting(c -> ((org.springframework.web.server.ResponseStatusException) c).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("403 against an expired token is also a session statement, so 401")
    void revision_403_with_expired_token_should_surface_as_401() {
        assertThatThrownBy(() -> service(revisionAnswers(HttpStatus.FORBIDDEN)).buildContext(expiredJwt(), java.util.List.of()))
                .isInstanceOf(AuthorizationContextCache.RevisionUnavailableException.class)
                .cause()
                .extracting(c -> ((org.springframework.web.server.ResponseStatusException) c).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * An unreachable or erroring permission-service is the platform's problem, not the session's,
     * and must stay FAIL-CLOSED: 503, never a cached grant reused as if the revision were unchanged.
     * That degradation is how a revoked grant used to survive its whole TTL.
     */
    @Test
    @DisplayName("upstream 5xx fails closed as 503, never reusing a cached grant")
    void revision_5xx_should_fail_closed_as_503() {
        assertThatThrownBy(() -> service(revisionAnswers(HttpStatus.BAD_GATEWAY)).buildContext(jwt(), java.util.List.of()))
                .isInstanceOf(AuthorizationContextCache.RevisionUnavailableException.class)
                .cause()
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(c -> {
                    org.springframework.web.server.ResponseStatusException rse =
                            (org.springframework.web.server.ResponseStatusException) c;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(rse.getReason()).isEqualTo("ERR_AUTHZ_REVISION_UNAVAILABLE");
                });
    }

    /**
     * A 200 carrying no usable revision is still an unknown revision — treating it as anything else
     * would reintroduce exactly the degradation the 503 exists to prevent.
     */
    @Test
    @DisplayName("200 without a usable revision is an unknown revision, so 503")
    void revision_200_without_version_should_fail_closed_as_503() {
        ExchangeFunction exchange = request -> request.url().getPath().endsWith("/api/v1/authz/version")
                ? Mono.just(json("{\"unexpected\":true}"))
                : Mono.just(json("{\"userId\":\"1\",\"permissions\":[],\"allowedScopes\":[]}"));
        assertThatThrownBy(() -> service(exchange).buildContext(jwt(), java.util.List.of()))
                .isInstanceOf(AuthorizationContextCache.RevisionUnavailableException.class)
                .cause()
                .extracting(c -> ((org.springframework.web.server.ResponseStatusException) c).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
