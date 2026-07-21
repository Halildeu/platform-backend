package com.example.ethics.security;

import com.example.ethics.config.EthicsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Faz 35 ES-306 residual — public endpoint rate limit. Per-IP fixed-window
 * bucket returns HTTP 429 with {@code Retry-After} once the threshold is
 * exceeded within a rolling minute window. Sits BEFORE the credential-boundary
 * filter (order = -20) so throttled traffic never reaches the boundary check
 * or the JPA layer. Persistence is process-local — a scale-out deployment
 * either accepts N-fold effective throughput or fronts with an ingress-level
 * limit (both are documented in the runbook).
 */
@Component
public class PublicRateLimitFilter extends OncePerRequestFilter {
    static final String PATH_PREFIX = "/api/v1/public/ethics/";
    private static final long WINDOW_MILLIS = 60_000L;

    private final EthicsProperties properties;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, AtomicReference<Window>> buckets = new ConcurrentHashMap<>();

    public PublicRateLimitFilter(EthicsProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit = properties.rateLimitPerMinute();
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }
        String key = bucketKey(request);
        long now = Instant.now().toEpochMilli();
        AtomicReference<Window> ref = buckets.computeIfAbsent(key, k -> new AtomicReference<>(new Window(now, 0)));
        Window updated = ref.updateAndGet(current -> {
            if (now - current.startMillis() >= WINDOW_MILLIS) {
                return new Window(now, 1);
            }
            return new Window(current.startMillis(), current.count() + 1);
        });
        if (updated.count() > limit) {
            long retryAfterSec = Math.max(1L, (WINDOW_MILLIS - (now - updated.startMillis())) / 1000L);
            writeRateLimited(request, response, retryAfterSec, limit);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String bucketKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String clientIp;
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            clientIp = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        } else {
            clientIp = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        }
        String host = request.getServerName() == null ? "unknown" : request.getServerName();
        return host + "|" + clientIp;
    }

    private void writeRateLimited(HttpServletRequest request, HttpServletResponse response, long retryAfter, int limit)
            throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        Object requestId = request.getAttribute(SensitiveResponseHeadersFilter.REQUEST_ID_ATTRIBUTE);
        mapper.writeValue(response.getOutputStream(), Map.of("error", Map.of(
                "code", "RATE_LIMITED",
                "message", "Bu ürün için istekler geçici olarak sınırlandırıldı. Lütfen daha sonra tekrar deneyiniz.",
                "retryAfterSeconds", retryAfter,
                "limit", limit,
                "requestId", requestId == null ? "unavailable" : requestId.toString())));
    }

    private record Window(long startMillis, int count) {}
}
