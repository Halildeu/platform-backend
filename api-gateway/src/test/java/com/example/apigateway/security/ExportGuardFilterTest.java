package com.example.apigateway.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class ExportGuardFilterTest {

    @Test
    void rateLimitExceededReturns429() {
        ExportGuardFilter filter = new ExportGuardFilter(1, 1); // 1 req/min, burst 1

        // First request allowed
        ServerWebExchange ex1 = exchange("/api/users/export.csv");
        filter.filter(ex1, e -> Mono.empty()).block();
        assertNull(ex1.getResponse().getStatusCode());

        // Second request immediately should be limited
        ServerWebExchange ex2 = exchange("/api/users/export.csv");
        filter.filter(ex2, e -> Mono.empty()).block();
        assertNotNull(ex2.getResponse().getStatusCode());
        assertEquals(429, ex2.getResponse().getStatusCode().value());
    }

    @Test
    void injectsPiiPolicyHeaderForExportRequests() {
        ExportGuardFilter filter = new ExportGuardFilter(60, 60);
        ServerWebExchange ex = exchange("/api/audit/events/export");
        filter.filter(ex, mutated -> {
            assertEquals("mask", mutated.getRequest().getHeaders().getFirst("X-PII-Policy"));
            return Mono.empty();
        }).block();
    }

    @Test
    void nonExportPathPassesThrough() {
        ExportGuardFilter filter = new ExportGuardFilter(1, 1);
        ServerWebExchange ex = exchange("/api/users/all");
        filter.filter(ex, e -> Mono.empty()).block();
        // no status set by filter
        assertNull(ex.getResponse().getStatusCode());
    }

    private ServerWebExchange exchange(String path) {
        MockServerHttpRequest req = MockServerHttpRequest.get(path).build();
        return MockServerWebExchange.from(req);
    }
}
