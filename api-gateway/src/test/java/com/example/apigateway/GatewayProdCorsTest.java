package com.example.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Preflight regression guard for non-local/prod CORS behaviour (P1.5).
 *
 * LocalDevCorsConfig is excluded under the {@code test} profile (the
 * {@code !local & !dev} expression matches {@code test}), so the only active
 * CorsWebFilter is {@link ProdCorsConfig}. Mirrors {@link GatewayLocalCorsTest}
 * but asserts exact-origin allowlist behaviour: preflight from an allowed
 * origin returns 200 with {@code Access-Control-Allow-Origin}; preflight from
 * a disallowed origin is rejected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ApiGatewayApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "gateway.cors.allowed-origins=https://stage.acik.test,https://ai.acik.com",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
        "spring.main.web-application-type=reactive"
})
class GatewayProdCorsTest {

    @LocalServerPort
    int port;

    @Autowired
    WebTestClient webClient;

    @Test
    void preflightFromAllowedOriginReturnsOkWithAcao() {
        webClient.options()
                .uri("http://127.0.0.1:" + port + "/api/v1/authz/me")
                .header("Origin", "https://stage.acik.test")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization,content-type,x-trace-id,cache-control")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://stage.acik.test")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
                .expectHeader().exists("Access-Control-Allow-Headers");
    }

    @Test
    void preflightFromSecondAllowedOriginAlsoReturnsOk() {
        webClient.options()
                .uri("http://127.0.0.1:" + port + "/api/v1/authz/me")
                .header("Origin", "https://ai.acik.com")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://ai.acik.com");
    }

    @Test
    void preflightFromDisallowedOriginIsRejected() {
        webClient.options()
                .uri("http://127.0.0.1:" + port + "/api/v1/authz/me")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isForbidden();
    }
}
