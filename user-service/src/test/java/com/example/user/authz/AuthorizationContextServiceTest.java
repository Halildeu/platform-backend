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
}
