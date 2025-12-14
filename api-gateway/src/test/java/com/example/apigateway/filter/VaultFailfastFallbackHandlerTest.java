package com.example.apigateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class VaultFailfastFallbackHandlerTest {

    private final VaultFailfastFallbackHandler handler = new VaultFailfastFallbackHandler(new ObjectMapper());

    @Test
    void handlesServiceUnavailableByReturningMaintenancePayload() {
        MockServerWebExchange exchange = MockServerWebExchange
                .from(org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/auth/sessions")
                        .header("X-Trace-Id", "trace-123"));

        handler.handle(exchange, new NotFoundException("Unable to find instance for user-service")).block();

        ServerHttpResponse response = exchange.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("X-Serban-Outage-Code")).isEqualTo("VAULT_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");

        String body = ((MockServerHttpResponse) response).getBodyAsString().block();
        assertThat(body).contains("\"error\":\"vault_unavailable\"");
        assertThat(body).contains("\"outageCode\":\"VAULT_UNAVAILABLE\"");
        assertThat(body).contains("\"traceId\":\"trace-123\"");
    }

    @Test
    void delegatesWhenExceptionIsNotVaultRelated() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/api/v1/auth/sessions")
        );

        StepVerifier.create(handler.handle(exchange, new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .expectError(ResponseStatusException.class)
                .verify();
    }
}
