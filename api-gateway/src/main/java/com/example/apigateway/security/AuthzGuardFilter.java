package com.example.apigateway.security;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rate limiter for authorization check endpoints (/api/v1/authz/check,
 * /api/v1/authz/batch-check). Prevents brute-force permission enumeration
 * and application-layer DoS on the OpenFGA authz path.
 *
 * Default: 60 checks/min per user, burst 120.
 * For prod with Redis, prefer RedisRateLimiter via SCG config.
 */
@Component
public class AuthzGuardFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthzGuardFilter.class);

    private final int replenishPerMinute;
    private final int burstCapacity;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public AuthzGuardFilter(
            @Value("${authz.rate-limit.per-minute:60}") int replenishPerMinute,
            @Value("${authz.rate-limit.burst:120}") int burstCapacity) {
        this.replenishPerMinute = Math.max(replenishPerMinute, 1);
        this.burstCapacity = Math.max(burstCapacity, this.replenishPerMinute);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!isAuthzCheckPath(path)) {
            return chain.filter(exchange);
        }

        String key = resolveKey(exchange.getRequest());
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(burstCapacity, replenishPerMinute));
        boolean allowed = bucket.tryConsume();
        if (!allowed) {
            log.warn("[authz-guard] rate-limit exceeded key={} path={}", maskKey(key), path);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().add("Retry-After", "60");
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    private boolean isAuthzCheckPath(String path) {
        if (path == null) return false;
        return path.startsWith("/api/v1/authz/check")
            || path.startsWith("/api/v1/authz/batch-check")
            || path.startsWith("/api/v1/authz/explain");
    }

    private String resolveKey(ServerHttpRequest request) {
        String sub = request.getHeaders().getFirst("X-User-Id");
        if (sub != null && !sub.isBlank()) {
            return "user:" + sub;
        }
        InetSocketAddress addr = request.getRemoteAddress();
        String host = addr != null ? Objects.toString(addr.getAddress(), null) : null;
        return host != null ? "ip:" + host : "anon";
    }

    private String maskKey(String key) {
        if (key == null) return "?";
        if (key.startsWith("user:")) return "user:***";
        if (key.startsWith("ip:")) return key.replaceAll("([0-9]+)$", "*");
        return key;
    }

    @Override
    public int getOrder() {
        return -40; // after ExportGuardFilter (-50), before routing
    }

    private static final class TokenBucket {
        private final int capacity;
        private final double refillPerSecond;
        private double tokens;
        private Instant lastRefill;

        private TokenBucket(int capacity, int replenishPerMinute) {
            this.capacity = capacity;
            this.refillPerSecond = replenishPerMinute / 60.0;
            this.tokens = capacity;
            this.lastRefill = Instant.now();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            Instant now = Instant.now();
            double seconds = Duration.between(lastRefill, now).toMillis() / 1000.0;
            if (seconds <= 0) return;
            tokens = Math.min(capacity, tokens + seconds * refillPerSecond);
            lastRefill = now;
        }
    }
}
