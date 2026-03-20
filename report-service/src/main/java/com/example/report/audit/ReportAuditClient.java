package com.example.report.audit;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class ReportAuditClient {

    private static final Logger log = LoggerFactory.getLogger(ReportAuditClient.class);
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final WebClient webClient;
    private final String internalApiKey;

    public ReportAuditClient(WebClient.Builder loadBalancedWebClientBuilder,
                             @Qualifier("plainWebClientBuilder") WebClient.Builder plainWebClientBuilder,
                             @Value("${permission.service.base-url:http://permission-service}") String baseUrl,
                             @Value("${report.audit.internal-api-key:}") String internalApiKey) {
        WebClient.Builder selectedBuilder = requiresDirectHttp(baseUrl) ? plainWebClientBuilder : loadBalancedWebClientBuilder;
        this.webClient = selectedBuilder.baseUrl(baseUrl).build();
        this.internalApiKey = internalApiKey;
    }

    public void logReportAccess(String reportKey, String userId, String userEmail) {
        sendAuditEvent("REPORT_ACCESS", userId, userEmail,
                reportKey + " report accessed",
                Map.of("reportKey", reportKey));
    }

    public void logReportExport(String reportKey, String userId, String userEmail, String format) {
        sendAuditEvent("REPORT_EXPORT", userId, userEmail,
                reportKey + " report exported as " + format,
                Map.of("reportKey", reportKey, "format", format));
    }

    public void logReportAccessDenied(String reportKey, String userId, String userEmail, String reason) {
        sendAuditEvent("REPORT_ACCESS_DENIED", userId, userEmail,
                reportKey + " report access denied: " + reason,
                Map.of("reportKey", reportKey, "reason", reason));
    }

    private void sendAuditEvent(String eventType, String userId, String userEmail,
                                 String details, Map<String, Object> metadata) {
        if (!StringUtils.hasText(internalApiKey)) {
            return;
        }

        Long performedBy = null;
        try {
            performedBy = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            // userId might be a UUID string
        }

        var request = new AuditEventIngestRequest(
                eventType,
                performedBy,
                details,
                userEmail,
                "report-service",
                "INFO",
                eventType,
                resolveCorrelationId(),
                metadata,
                null,
                null,
                Instant.now()
        );

        try {
            var spec = webClient.post()
                    .uri("/api/v1/internal/audit/events")
                    .header(INTERNAL_API_KEY_HEADER, internalApiKey)
                    .contentType(MediaType.APPLICATION_JSON);
            String traceId = MDC.get("traceId");
            if (StringUtils.hasText(traceId)) {
                spec.header(TRACE_ID_HEADER, traceId);
            }
            spec.bodyValue(request)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn("Report audit mirror HTTP error: status={} message={}", ex.getStatusCode(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("Report audit mirror failed: {}", ex.getMessage());
        }
    }

    private String resolveCorrelationId() {
        String traceId = MDC.get("traceId");
        return StringUtils.hasText(traceId) ? traceId : UUID.randomUUID().toString();
    }

    private boolean requiresDirectHttp(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }
            return "localhost".equalsIgnoreCase(host)
                    || host.contains(".")
                    || host.contains(":");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private record AuditEventIngestRequest(
            String eventType,
            Long performedBy,
            String details,
            String userEmail,
            String service,
            String level,
            String action,
            String correlationId,
            Map<String, Object> metadata,
            Map<String, Object> before,
            Map<String, Object> after,
            Instant occurredAt
    ) {}
}
