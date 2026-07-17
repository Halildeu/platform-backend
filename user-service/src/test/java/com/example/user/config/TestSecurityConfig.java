package com.example.user.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@TestConfiguration
public class TestSecurityConfig {

    /**
     * Stands in for permission-service (board #2556).
     *
     * <p>Since the authorization cache became revision-aware, every authorized request reads
     * {@code /authz/version}, and an unreadable revision is refused rather than assumed unchanged —
     * that refusal is what stops a revoked grant from surviving (~271s was measured on k3d-test).
     * These tests have no permission-service: the call used to fail on DNS and {@code loadContext}
     * quietly fell back to JWT-derived authority.
     *
     * <p>Answering the revision here — while leaving {@code /authz/me} unavailable — keeps these
     * tests on exactly the path they were written against, declares the dependency instead of
     * relying on a DNS failure, and drops the multi-second stall each call used to cost.
     *
     * <p>A test that means to assert on authority coming from permission-service should stub
     * {@code /authz/me} with the shape it needs rather than lean on this fallback.
     */
    @Bean(name = "plainWebClientBuilder")
    @Primary
    public org.springframework.web.reactive.function.client.WebClient.Builder testPlainWebClientBuilder() {
        return org.springframework.web.reactive.function.client.WebClient.builder()
                .exchangeFunction(request -> {
                    if (request.url().getPath().endsWith("/api/v1/authz/version")) {
                        return reactor.core.publisher.Mono.just(
                                org.springframework.web.reactive.function.client.ClientResponse
                                        .create(org.springframework.http.HttpStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .body("{\"authzVersion\":1}")
                                        .build());
                    }
                    return reactor.core.publisher.Mono.just(
                            org.springframework.web.reactive.function.client.ClientResponse
                                    .create(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                                    .build());
                });
    }

    @Bean
    @Primary
    public RSAKey testJwtRsaKey() throws Exception {
        return new RSAKeyGenerator(2048)
                .keyID("test-key")
                .generate();
    }

    @Bean
    @Primary
    public JwtEncoder testJwtEncoder(RSAKey testJwtRsaKey) {
        JWKSet jwkSet = new JWKSet(testJwtRsaKey);
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(jwkSet));
    }

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder(RSAKey testJwtRsaKey) throws Exception {
        return NimbusJwtDecoder.withPublicKey(testJwtRsaKey.toRSAPublicKey()).build();
    }
}
