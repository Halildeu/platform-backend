package com.example.apigateway;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {ApiGatewayApplication.class, GatewaySecurityTest.JwtTestConfig.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.gateway.discovery.locator.enabled=false",
        "spring.main.web-application-type=reactive"
})
class GatewaySecurityTest {

    @LocalServerPort
    int port;

    @Autowired
    private org.springframework.core.env.Environment environment;

    static MockWebServer stub;

    @Autowired
    JwtEncoder jwtEncoder;

    @BeforeAll
    static void startWiremock() throws Exception {
        stub = new MockWebServer();
        Dispatcher dispatcher = new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path != null && path.startsWith("/api/users/all")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("{\"items\":[],\"total\":0}");
                }
                if (path != null && path.equals("/api/users/export.csv")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "text/plain")
                            .setBody("id,fullName\n");
                }
                if (path != null && path.equals("/api/variants/notfound")) {
                    return new MockResponse().setResponseCode(404);
                }
                if (path != null && path.equals("/api/variants/error")) {
                    return new MockResponse().setResponseCode(500);
                }
                if (path != null && path.startsWith("/api/variants")) {
                    return new MockResponse().setResponseCode(200)
                            .addHeader("Content-Type", "application/json")
                            .setBody("[]");
                }
                return new MockResponse().setResponseCode(404);
            }
        };
        stub.setDispatcher(dispatcher);
        stub.start();
    }

    @AfterAll
    static void stopWiremock() throws Exception {
        if (stub != null) stub.shutdown();
    }

    @DynamicPropertySource
    static void routeProps(DynamicPropertyRegistry reg) {
        reg.add("spring.cloud.gateway.routes[0].id", () -> "user-service-route");
        reg.add("spring.cloud.gateway.routes[0].uri", () -> stub.url("/").toString());
        reg.add("spring.cloud.gateway.routes[0].predicates[0]", () -> "Path=/api/users/**");

        reg.add("spring.cloud.gateway.routes[1].id", () -> "variant-service-route");
        reg.add("spring.cloud.gateway.routes[1].uri", () -> stub.url("/").toString());
        reg.add("spring.cloud.gateway.routes[1].predicates[0]", () -> "Path=/api/variants/**");

        reg.add("SECURITY_JWT_ISSUER", () -> "auth-service");
        reg.add("SECURITY_JWT_AUDIENCE", () -> "user-service,frontend");
    }

    private String token() {
        return token(List.of("user-service"));
    }

    private String token(List<String> audiences) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("admin@example.com")
                .issuer("auth-service")
                .audience(audiences)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("userId", 1)
                .build();
        var headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    @Autowired
    WebTestClient webClient;

    @Test
    void users_requires_jwt() {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "dev"))) {
            webClient.get().uri("http://localhost:" + port + "/api/users/all?page=1&pageSize=1")
                    .exchange()
                    .expectStatus().isOk();
        } else {
            webClient.get().uri("http://localhost:" + port + "/api/users/all?page=1&pageSize=1")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Test
    void variants_requires_jwt() {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local", "dev"))) {
            webClient.get().uri("http://localhost:" + port + "/api/variants?gridId=test")
                    .exchange()
                    .expectStatus().isOk();
        } else {
            webClient.get().uri("http://localhost:" + port + "/api/variants?gridId=test")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @Test
    void users_with_jwt_forwards_200() {
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/users/all?page=1&pageSize=1")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void users_with_frontendAudienceToken_forwards200() {
        String t = token(List.of("frontend"));
        webClient.get().uri("http://localhost:" + port + "/api/users/all?page=1&pageSize=1")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void variants_with_jwt_forwards_200() {
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/variants?gridId=test")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void variants_404_500_propagate() {
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/variants/notfound")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isNotFound();

        webClient.get().uri("http://localhost:" + port + "/api/variants/error")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void export_injects_pii_policy_header() throws Exception {
        String t = token();
        webClient.get().uri("http://localhost:" + port + "/api/users/export.csv")
                .header("Authorization", "Bearer " + t)
                .exchange()
                .expectStatus().isOk();

        // Kuyruktan export isteğini yakala ve header'ı doğrula
        RecordedRequest req;
        RecordedRequest exportReq = null;
        for (int i = 0; i < 10; i++) {
            req = stub.takeRequest();
            if (req == null) break;
            if ("/api/users/export.csv".equals(req.getPath())) { exportReq = req; break; }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(exportReq, "export request not captured");
        org.junit.jupiter.api.Assertions.assertEquals("mask", exportReq.getHeader("X-PII-Policy"));
    }

    @Configuration
    static class JwtTestConfig {
        @Bean
        public com.nimbusds.jose.jwk.RSAKey testRsaKey() throws Exception {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            java.security.KeyPair kp = kpg.generateKeyPair();
            return new com.nimbusds.jose.jwk.RSAKey.Builder((java.security.interfaces.RSAPublicKey) kp.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) kp.getPrivate())
                    .keyID("test-kid")
                    .build();
        }

        @Bean
        @Primary
        public JwtEncoder testJwtEncoder(com.nimbusds.jose.jwk.RSAKey testRsaKey) {
            JWKSource<com.nimbusds.jose.proc.SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(testRsaKey));
            return new NimbusJwtEncoder(jwkSource);
        }

        @Bean
        @Primary
        public JwtDecoder testJwtDecoder(com.nimbusds.jose.jwk.RSAKey testRsaKey) {
            try {
                return NimbusJwtDecoder.withPublicKey(testRsaKey.toRSAPublicKey()).build();
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new RuntimeException(e);
            }
        }

        @Bean
        @Primary
        public org.springframework.security.oauth2.jwt.ReactiveJwtDecoder testReactiveJwtDecoder(com.nimbusds.jose.jwk.RSAKey testRsaKey) {
            try {
                return org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder.withPublicKey(testRsaKey.toRSAPublicKey()).build();
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
