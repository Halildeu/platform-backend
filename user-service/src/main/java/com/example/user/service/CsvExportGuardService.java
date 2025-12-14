package com.example.user.service;

import com.example.user.model.User;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class CsvExportGuardService {

    private final int burstCapacity;
    private final double refillPerSecond;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final UserAuditEventService userAuditEventService;
    private final Counter rateLimitAllowedCounter;
    private final Counter rateLimitBlockedCounter;
    private final Counter auditSuccessCounter;
    private final Counter auditFailureCounter;
    private final Timer exportDurationTimer;

    public CsvExportGuardService(@Value("${export.rate-limit.per-minute:12}") int replenishPerMinute,
                                 @Value("${export.rate-limit.burst:24}") int burstCapacity,
                                 UserAuditEventService userAuditEventService,
                                 MeterRegistry meterRegistry) {
        int safePerMinute = Math.max(replenishPerMinute, 1);
        this.refillPerSecond = safePerMinute / 60.0;
        this.burstCapacity = Math.max(burstCapacity, safePerMinute);
        this.userAuditEventService = userAuditEventService;
        this.rateLimitAllowedCounter = Counter.builder("users_export_rate_limit_total")
                .description("CSV export guard istekleri (allow)")
                .tag("result", "allowed")
                .register(meterRegistry);
        this.rateLimitBlockedCounter = Counter.builder("users_export_rate_limit_total")
                .description("CSV export guard istekleri (block)")
                .tag("result", "blocked")
                .register(meterRegistry);
        this.auditSuccessCounter = Counter.builder("users_export_audit_total")
                .description("CSV export audit kayıtları (başarılı)")
                .tag("result", "success")
                .register(meterRegistry);
        this.auditFailureCounter = Counter.builder("users_export_audit_total")
                .description("CSV export audit kayıtları (başarısız)")
                .tag("result", "failure")
                .register(meterRegistry);
        this.exportDurationTimer = Timer.builder("users_export_duration")
                .description("CSV export streaming süresi")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void assertWithinLimit(User user) {
        String key = resolveKey(user);
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(this.burstCapacity, this.refillPerSecond));
        if (!bucket.tryConsume()) {
            rateLimitBlockedCounter.increment();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "export_rate_limit");
        }
        rateLimitAllowedCounter.increment();
    }

    public void recordAudit(User performer,
                            String search,
                            String status,
                            String role,
                            String sort,
                            String advancedFilter,
                            long rowCount,
                            long durationMs,
                            boolean success,
                            String failureReason) {
        Long performerId = performer != null ? performer.getId() : null;
        String details = buildDetails(search, status, role, sort, advancedFilter, rowCount, durationMs, success, failureReason);
        userAuditEventService.recordExportEvent(performerId, details, success);
        if (success) {
            auditSuccessCounter.increment();
        } else {
            auditFailureCounter.increment();
        }
        if (durationMs > 0) {
            exportDurationTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    private String resolveKey(User user) {
        if (user != null) {
            if (user.getId() != null) {
                return "user:" + user.getId();
            }
            if (user.getEmail() != null) {
                return "email:" + user.getEmail().toLowerCase();
            }
        }
        return "anon";
    }

    private String buildDetails(String search,
                                String status,
                                String role,
                                String sort,
                                String advancedFilter,
                                long rowCount,
                                long durationMs,
                                boolean success,
                                String failureReason) {
        StringBuilder sb = new StringBuilder("csv_export{");
        sb.append("success=").append(success);
        sb.append(", rows=").append(rowCount);
        sb.append(", durationMs=").append(durationMs);
        sb.append(", search='").append(sanitize(search, 64)).append("'");
        sb.append(", status='").append(sanitize(status, 32)).append("'");
        sb.append(", role='").append(sanitize(role, 32)).append("'");
        sb.append(", sort='").append(sanitize(sort, 64)).append("'");
        sb.append(", advancedFilter='").append(sanitizeAdvancedFilter(advancedFilter)).append("'");
        if (!success && StringUtils.hasText(failureReason)) {
            sb.append(", error='").append(sanitize(failureReason, 120)).append("'");
        }
        sb.append('}');
        return sb.toString();
    }

    private String sanitize(String input, int maxLen) {
        if (!StringUtils.hasText(input)) {
            return "";
        }
        String cleaned = input.replaceAll("[\\r\\n]+", " ").trim();
        if (cleaned.length() > maxLen) {
            return cleaned.substring(0, maxLen) + "...";
        }
        return cleaned;
    }

    private String sanitizeAdvancedFilter(String advancedFilter) {
        if (!StringUtils.hasText(advancedFilter)) {
            return "";
        }
        try {
            String decoded = URLDecoder.decode(advancedFilter, StandardCharsets.UTF_8);
            return sanitize(decoded, 512);
        } catch (Exception ex) {
            return "[decode-error]";
        }
    }

    private static final class TokenBucket {
        private final int capacity;
        private final double refillPerSecond;
        private double tokens;
        private Instant lastRefill;

        private TokenBucket(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
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
            if (seconds <= 0) {
                return;
            }
            tokens = Math.min(capacity, tokens + seconds * refillPerSecond);
            lastRefill = now;
        }
    }
}
