package com.example.user.permission;

import java.net.URI;
import java.time.Instant;
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

@Component
public class UserAuditMirrorClient {

    private static final Logger log = LoggerFactory.getLogger(UserAuditMirrorClient.class);
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final WebClient webClient;
    private final boolean enabled;
    private final String internalApiKey;

    public UserAuditMirrorClient(@Qualifier("loadBalancedWebClientBuilder") WebClient.Builder webClientBuilder,
                                 @Qualifier("directWebClientBuilder") WebClient.Builder directWebClientBuilder,
                                 @Value("${permission.audit-mirror.base-url:${permission.service.base-url:http://permission-service}}") String baseUrl,
                                 @Value("${permission.audit-mirror.enabled:true}") boolean enabled,
                                 @Value("${permission.audit-mirror.internal-api-key:}") String internalApiKey) {
        WebClient.Builder selectedBuilder = requiresDirectHttp(baseUrl) ? directWebClientBuilder : webClientBuilder;
        this.webClient = selectedBuilder.baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.internalApiKey = internalApiKey;
    }

    public void mirrorSessionTimeoutSync(Long performedBy,
                                         String userEmail,
                                         String details,
                                         Map<String, Object> metadata,
                                         Map<String, Object> before,
                                         Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_SESSION_TIMEOUT_SYNCED",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorSessionTimeoutConflict(Long performedBy,
                                             String userEmail,
                                             String details,
                                             Map<String, Object> metadata,
                                             Map<String, Object> before,
                                             Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_SESSION_TIMEOUT_SYNC_CONFLICT",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorNotificationPreferenceSync(Long performedBy,
                                                 String userEmail,
                                                 String details,
                                                 Map<String, Object> metadata,
                                                 Map<String, Object> before,
                                                 Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_NOTIFICATION_PREFERENCE_SYNCED",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorNotificationPreferenceConflict(Long performedBy,
                                                     String userEmail,
                                                     String details,
                                                     Map<String, Object> metadata,
                                                     Map<String, Object> before,
                                                     Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_NOTIFICATION_PREFERENCE_SYNC_CONFLICT",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorLocaleSync(Long performedBy,
                                 String userEmail,
                                 String details,
                                 Map<String, Object> metadata,
                                 Map<String, Object> before,
                                 Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_LOCALE_SYNCED",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorLocaleConflict(Long performedBy,
                                     String userEmail,
                                     String details,
                                     Map<String, Object> metadata,
                                     Map<String, Object> before,
                                     Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_LOCALE_SYNC_CONFLICT",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorTimezoneSync(Long performedBy,
                                   String userEmail,
                                   String details,
                                   Map<String, Object> metadata,
                                   Map<String, Object> before,
                                   Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_TIMEZONE_SYNCED",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorTimezoneConflict(Long performedBy,
                                       String userEmail,
                                       String details,
                                       Map<String, Object> metadata,
                                       Map<String, Object> before,
                                       Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_TIMEZONE_SYNC_CONFLICT",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorDateFormatSync(Long performedBy,
                                     String userEmail,
                                     String details,
                                     Map<String, Object> metadata,
                                     Map<String, Object> before,
                                     Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_DATE_FORMAT_SYNCED",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorDateFormatConflict(Long performedBy,
                                         String userEmail,
                                         String details,
                                         Map<String, Object> metadata,
                                         Map<String, Object> before,
                                         Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_DATE_FORMAT_SYNC_CONFLICT",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorTimeFormatSync(Long performedBy,
                                     String userEmail,
                                     String details,
                                     Map<String, Object> metadata,
                                     Map<String, Object> before,
                                     Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_TIME_FORMAT_SYNCED",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    public void mirrorTimeFormatConflict(Long performedBy,
                                         String userEmail,
                                         String details,
                                         Map<String, Object> metadata,
                                         Map<String, Object> before,
                                         Map<String, Object> after) {
        mirrorUserAuditEvent(
                "USER_TIME_FORMAT_SYNC_CONFLICT",
                performedBy,
                userEmail,
                details,
                metadata,
                before,
                after
        );
    }

    private void mirrorUserAuditEvent(String eventType,
                                      Long performedBy,
                                      String userEmail,
                                      String details,
                                      Map<String, Object> metadata,
                                      Map<String, Object> before,
                                      Map<String, Object> after) {
        if (!enabled || !StringUtils.hasText(internalApiKey) || performedBy == null || !StringUtils.hasText(userEmail)) {
            return;
        }

        AuditEventIngestRequest request = new AuditEventIngestRequest(
                eventType,
                performedBy,
                details,
                userEmail,
                "user-service",
                "INFO",
                eventType,
                resolveCorrelationId(),
                metadata,
                before,
                after,
                Instant.now()
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
            log.warn("User audit mirror HTTP hata verdi. status={} message={}", ex.getStatusCode(), ex.getMessage());
        } catch (Exception ex) {
            log.warn("User audit mirror basarisiz oldu: {}", ex.getMessage());
        }
    }

    private String resolveCorrelationId() {
        String traceId = MDC.get("traceId");
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }
        return java.util.UUID.randomUUID().toString();
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
