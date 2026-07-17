package com.example.user.authz;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Stands in for permission-service in Spring context tests (board #2556).
 *
 * <p>Since the authorization cache became revision-aware, every authorized request reads
 * {@code /authz/version}, and an unreadable revision is refused rather than treated as "unchanged"
 * — that refusal is the fix for a revoked grant surviving ~271s. These tests never had a
 * permission-service to talk to: the call used to fail on DNS and {@code loadContext} quietly fell
 * back to JWT-derived authority.
 *
 * <p>So the revision is answered here while {@code /authz/me} stays unavailable, which keeps the
 * tests exercising exactly the path they always did — but declares the dependency instead of
 * depending on a DNS failure, and drops the multi-second stall each call used to cost.
 *
 * <p>Deliberately NOT a stand-in for the real contract: a test that needs authority to come from
 * permission-service should stub {@code /authz/me} with the shape it means to assert against, not
 * lean on this fallback.
 */
@TestConfiguration
public class TestAuthzUpstreamConfig {

    @Bean(name = "plainWebClientBuilder")
    @Primary
    public WebClient.Builder testPlainWebClientBuilder() {
        return WebClient.builder().exchangeFunction(request -> {
                                if (request.url().getPath().endsWith("/api/v1/authz/version")) {
                        return reactor.core.publisher.Mono.just(
                                org.springframework.web.reactive.function.client.ClientResponse
                                        .create(org.springframework.http.HttpStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .body("{\"authzVersion\":1}")
                                        .build());
                    }
                    // /authz/me: authority now comes ONLY from permission-service — the JWT fallback
                    // was removed (it made the token its own authority). So the stub must answer with
                    // the permissions these suites assert against, rather than letting the call fail.
                    return reactor.core.publisher.Mono.just(
                            org.springframework.web.reactive.function.client.ClientResponse
                                    .create(org.springframework.http.HttpStatus.OK)
                                    .header("Content-Type", "application/json")
                                    .body("{\"userId\":\"1\",\"permissions\":[\"VIEW_USERS\",\"MANAGE_USERS\"],"
                                            + "\"allowedScopes\":[],\"superAdmin\":false}")
                                    .build());
        });
    }
}
