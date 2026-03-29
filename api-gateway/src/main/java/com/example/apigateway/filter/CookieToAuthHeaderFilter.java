package com.example.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reads JWT from httpOnly cookie and injects it as Authorization header.
 *
 * Flow:
 *   Browser sends cookie: erp_access_token=eyJ...
 *   This filter reads cookie → sets Authorization: Bearer eyJ...
 *   Downstream services receive standard Bearer token
 *
 * If Authorization header already exists (direct API call), cookie is ignored.
 * Cookie name configurable via erp.auth.cookie-name property.
 */
@Component
public class CookieToAuthHeaderFilter implements GlobalFilter, Ordered {

    private static final String COOKIE_NAME = "erp_access_token";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Skip if Authorization header already present
        if (request.getHeaders().containsKey("Authorization")) {
            return chain.filter(exchange);
        }

        // Read token from httpOnly cookie
        HttpCookie cookie = request.getCookies().getFirst(COOKIE_NAME);
        if (cookie != null && !cookie.getValue().isBlank()) {
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("Authorization", "Bearer " + cookie.getValue())
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Run before security filters
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
