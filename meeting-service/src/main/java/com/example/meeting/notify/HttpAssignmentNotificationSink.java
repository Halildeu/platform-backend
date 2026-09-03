package com.example.meeting.notify;

import com.example.meeting.config.MeetingNotifyProperties;
import com.example.meeting.events.MeetingEventMessage;
import com.example.meeting.service.AssigneeDirectoryClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Delivers {@code meeting.action.assigned} / {@code meeting.action.reassigned}
 * outbox events to notification-orchestrator as fixed-copy system intents for
 * the (new) assignee (Faz 24 Görevler dilim-4b, gitops#3486 / #3537).
 *
 * <p>Contract kept deliberately narrow:
 * <ul>
 *   <li>recipient = the platform user behind {@code assigneeSubject}, resolved via
 *       user-service ({@code users:internal}); an unknown subject is a no-op (logged),
 *       not a retry storm;</li>
 *   <li>idempotency key = the outbox {@code event_key} (occurrence-scoped), so an
 *       outbox retry after a Redis failure replays instead of duplicating;</li>
 *   <li>payload is empty — the template carries fixed copy; no action text, meeting
 *       title or identities leave meeting-service through this channel;</li>
 *   <li>any transport / 5xx failure throws so the poller retries the row.</li>
 * </ul>
 */
public class HttpAssignmentNotificationSink implements AssignmentNotificationSink {

    static final String ASSIGNED = "meeting.action.assigned";
    static final String REASSIGNED = "meeting.action.reassigned";
    static final int TEMPLATE_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(HttpAssignmentNotificationSink.class);

    private final MeetingNotifyProperties properties;
    private final NotifyIntentTokenProvider tokens;
    private final AssigneeDirectoryClient directory;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpAssignmentNotificationSink(
            MeetingNotifyProperties properties,
            NotifyIntentTokenProvider tokens,
            AssigneeDirectoryClient directory,
            RestClient.Builder builder,
            ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMillis());
        requestFactory.setReadTimeout(properties.getResponseTimeoutMillis());
        this.properties = properties;
        this.tokens = tokens;
        this.directory = directory;
        this.restClient = builder.clone().requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    HttpAssignmentNotificationSink(
            MeetingNotifyProperties properties,
            NotifyIntentTokenProvider tokens,
            AssigneeDirectoryClient directory,
            RestClient restClient,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokens = tokens;
        this.directory = directory;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean handles(String eventType) {
        return ASSIGNED.equals(eventType) || REASSIGNED.equals(eventType);
    }

    @Override
    public void deliver(MeetingEventMessage message) {
        if (!handles(message.eventType())) {
            return;
        }
        String assigneeSubject = assigneeSubject(message.payloadJson());
        if (assigneeSubject == null) {
            // Unassignment (assignee cleared) carries nobody to notify.
            return;
        }
        Optional<Long> subscriber = directory.resolveUserId(properties.getSubjectIssuer(), assigneeSubject);
        if (subscriber.isEmpty()) {
            // Safe telemetry only: the event key, never the subject.
            log.warn("assignment notification skipped — assignee unknown to directory eventKey={}",
                    message.eventKey());
            return;
        }
        Map<String, Object> body = intent(message, subscriber.get());
        try {
            post(body);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                tokens.invalidate();
                post(body);
                return;
            }
            throw ex;
        }
    }

    private void post(Map<String, Object> body) {
        restClient.post()
                .uri(properties.getOrchestratorBaseUrl() + "/api/v1/internal/notify/intents")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    Map<String, Object> intent(MeetingEventMessage message, long subscriberId) {
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("type", "subscriber");
        recipient.put("subscriberId", Long.toString(subscriberId));
        recipient.put("locale", properties.getLocale());

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("templateId", message.eventType());
        template.put("version", TEMPLATE_VERSION);
        template.put("locale", properties.getLocale());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intentId", intentId(message.eventKey()));
        body.put("idempotencyKey", message.eventKey());
        body.put("correlationId", message.meetingId().toString());
        body.put("orgId", (message.orgId() != null ? message.orgId() : message.tenantId()).toString());
        body.put("topicKey", message.eventType());
        body.put("severity", "info");
        body.put("dataClassification", "transactional");
        body.put("recipients", List.of(recipient));
        body.put("template", template);
        body.put("channels", List.of(properties.getChannel()));
        body.put("payload", Map.of());
        return body;
    }

    /** Deterministic, orchestrator-safe ({@code ^[a-zA-Z0-9_-]+$}, max 64) id derived from the event key. */
    static String intentId(String eventKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(eventKey.getBytes(StandardCharsets.UTF_8));
            return "mtg-" + HexFormat.of().formatHex(digest, 0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String assigneeSubject(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode node = root.path("assigneeSubject");
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            String value = node.asText();
            return value.isBlank() ? null : value;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("meeting event payload is not readable", e);
        }
    }

    /** Wraps transport failures so the poller's retry path sees one exception class. */
    public static final class DeliveryFailedException extends RuntimeException {
        public DeliveryFailedException(String message, RestClientException cause) {
            super(message, cause);
        }
    }
}
