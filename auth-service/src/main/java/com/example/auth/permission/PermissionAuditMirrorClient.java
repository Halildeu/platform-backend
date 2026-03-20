package com.example.auth.permission;

import java.time.Instant;
import java.net.URI;
import java.util.Map;

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

import com.example.auth.model.AuthAuditEvent;

@Component
public class PermissionAuditMirrorClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionAuditMirrorClient.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final WebClient webClient;
    private final boolean enabled;
    private final String internalApiKey;

    public PermissionAuditMirrorClient(@Qualifier("loadBalancedWebClientBuilder") WebClient.Builder webClientBuilder,
                                       @Qualifier("plainWebClientBuilder") WebClient.Builder plainWebClientBuilder,
                                       @Value("${permission.audit-mirror.base-url:${permission.service.base-url:http://permission-service}}") String baseUrl,
                                       @Value("${permission.audit-mirror.enabled:true}") boolean enabled,
                                       @Value("${permission.audit-mirror.internal-api-key:}") String internalApiKey) {
        WebClient.Builder selectedBuilder = requiresDirectHttp(baseUrl) ? plainWebClientBuilder : webClientBuilder;
        this.webClient = selectedBuilder.baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.internalApiKey = internalApiKey;
    }

    public void mirror(AuthAuditEvent event,
                       Map<String, Object> metadata,
                       Map<String, Object> before,
                       Map<String, Object> after) {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(internalApiKey)) {
            log.debug("Permission audit mirror icin internal API key tanimli degil; mirror atlanacak.");
            return;
        }
        if (event == null) {
            return;
        }

        AuditEventIngestRequest request = new AuditEventIngestRequest(
                event.getEventType(),
                event.getPerformedBy(),
                event.getDetails(),
                event.getUserEmail(),
                event.getService(),
                event.getLevel(),
                event.getAction(),
                event.getCorrelationId(),
                metadata,
                before,
                after,
                event.getOccurredAt()
        );

        try {
            WebClient.RequestBodySpec spec = webClient.post()
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
            log.warn("Permission audit mirror HTTP hata verdi. status={} message={}", ex.getStatusCode(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("Permission audit mirror basarisiz oldu: {}", ex.getMessage());
        }
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
    ) {
    }
}
