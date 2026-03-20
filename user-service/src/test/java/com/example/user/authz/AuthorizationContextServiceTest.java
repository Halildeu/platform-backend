package com.example.user.authz;

import com.example.commonauth.AuthorizationContext;
import com.example.commonauth.AuthorizationContextCache;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;
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

class AuthorizationContextServiceTest {

    @Test
    void buildContext_should_expand_legacy_permissions_from_authz_me_response() {
        ExchangeFunction exchangeFunction = request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  "userId": "1",
                                  "permissions": ["VIEW_USERS", "MANAGE_USERS"],
                                  "allowedScopes": [],
                                  "superAdmin": true
                                }
                                """)
                        .build()
        );

        AuthorizationContextService service = new AuthorizationContextService(
                WebClient.builder().exchangeFunction(exchangeFunction),
                new AuthorizationContextCache(Duration.ofMinutes(1)),
                "http://permission-service"
        );

        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .subject("admin@example.com")
                .claim("email", "admin@example.com")
                .build();

        AuthorizationContext context = service.buildContext(jwt, Collections.emptyList());

        assertThat(context.getUserId()).isEqualTo(1L);
        assertThat(context.hasPermission("user-read")).isTrue();
        assertThat(context.hasPermission("user-create")).isTrue();
        assertThat(context.hasPermission("user-update")).isTrue();
        assertThat(context.hasPermission("user-delete")).isTrue();
        assertThat(context.hasPermission("user-export")).isTrue();
        assertThat(context.hasPermission("user-import")).isTrue();
    }

    @Test
    void buildContext_should_refresh_when_cached_context_has_no_permissions() {
        AtomicInteger callCount = new AtomicInteger();
        ExchangeFunction exchangeFunction = request -> {
            int current = callCount.getAndIncrement();
            String body = current == 0
                    ? """
                            {
                              "userId": "1",
                              "permissions": [],
                              "allowedScopes": [],
                              "superAdmin": false
                            }
                            """
                    : """
                            {
                              "userId": "1",
                              "permissions": ["VIEW_USERS"],
                              "allowedScopes": [],
                              "superAdmin": false
                            }
                            """;
            return Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build()
            );
        };

        AuthorizationContextService service = new AuthorizationContextService(
                WebClient.builder().exchangeFunction(exchangeFunction),
                new AuthorizationContextCache(Duration.ofMinutes(1)),
                "http://permission-service"
        );

        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .subject("admin@example.com")
                .claim("email", "admin@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthorizationContext first = service.buildContext(jwt, Collections.emptyList());
        AuthorizationContext second = service.buildContext(jwt, Collections.emptyList());

        assertThat(first.getPermissions()).isEmpty();
        assertThat(second.hasPermission("user-read")).isTrue();
        assertThat(callCount).hasValue(2);
    }
}
