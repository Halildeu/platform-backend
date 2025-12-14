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
 * Lightweight in-memory rate limiter + PII mask header for export endpoints.
 * Intended for dev/stage without Redis. For prod, prefer RedisRateLimiter via SCG config.
 */
@Component
public class ExportGuardFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ExportGuardFilter.class);

    private final int replenishPerMinute;
    private final int burstCapacity;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public ExportGuardFilter(
            @Value("${export.rate-limit.per-minute:12}") int replenishPerMinute,
            @Value("${export.rate-limit.burst:24}") int burstCapacity) {
        this.replenishPerMinute = Math.max(replenishPerMinute, 1);
        this.burstCapacity = Math.max(burstCapacity, this.replenishPerMinute);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        // Guard only export endpoints
        if (!isExportPath(path)) {
            return chain.filter(exchange);
        }

        String key = resolveKey(exchange.getRequest());
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(burstCapacity, replenishPerMinute));
        boolean allowed = bucket.tryConsume();
        if (!allowed) {
            log.warn("[export-guard] rate-limit exceeded key={} path={}", maskKey(key), path);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }

        // Add PII policy header to downstream request
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-PII-Policy", "mask")
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();

        log.info("[export-guard] allow key={} path={} qs={}", maskKey(key), path, maskQuery(mutated.getURI().getRawQuery()));
        return chain.filter(mutatedExchange);
    }

    private boolean isExportPath(String path) {
        if (path == null) return false;
        return path.equals("/api/users/export.csv") || path.equals("/api/audit/events/export");
    }

    private String resolveKey(ServerHttpRequest request) {
        String email = request.getHeaders().getFirst("X-User-Email");
        if (email != null && !email.isBlank()) {
            return "email:" + email.toLowerCase();
        }
        InetSocketAddress addr = request.getRemoteAddress();
        String host = addr != null ? Objects.toString(addr.getAddress(), null) : null;
        return host != null ? "ip:" + host : "anon";
    }

    private String maskKey(String key) {
        if (key == null) return "?";
        if (key.startsWith("email:")) return "email:*";
        if (key.startsWith("ip:")) return key.replaceAll("(\n|\r)", "").replaceAll("([0-9]+)$", "*");
        return key;
    }

    private String maskQuery(String qs) {
        if (qs == null) return "";
        return qs.replaceAll("(?i)(email|user|search)=([^&]+)", "$1=***");
    }

    @Override
    public int getOrder() {
        return -50; // before routing
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

