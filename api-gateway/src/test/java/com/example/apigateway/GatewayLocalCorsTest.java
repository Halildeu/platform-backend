package com.example.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ApiGatewayApplication.class)
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
        "spring.main.web-application-type=reactive"
})
class GatewayLocalCorsTest {

    @LocalServerPort
    int port;

    @Autowired
    WebTestClient webClient;

    @Test
    void preflightForAuthSessionsAllowsExpoWebOrigin() {
        webClient.options()
                .uri("http://127.0.0.1:" + port + "/api/v1/auth/sessions")
                .header("Origin", "http://127.0.0.1:8082")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://127.0.0.1:8082");
    }
}
