package com.example.user.notify;

import com.example.user.event.PendingActivationUserProvisionedEvent;
import com.example.user.serviceauth.ServiceTokenProvider;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Submits the #734 admin "new user awaiting activation" email to
 * notification-orchestrator's INTERNAL system-submit endpoint
 * ({@code POST /api/v1/internal/notify/intents}) using a short-lived
 * auth-service service token (aud=notification-orchestrator,
 * perm=notify:intents:system). Best-effort: every failure is swallowed + logged
 * so a notification hiccup NEVER affects the login / provisioning flow.
 */
@Component
public class PendingActivationNotificationClient {

    private static final Logger log = LoggerFactory.getLogger(PendingActivationNotificationClient.class);

    private final WebClient webClient;
    private final ServiceTokenProvider serviceTokenProvider;
    private final PendingActivationNotificationProperties props;

    public PendingActivationNotificationClient(
            @Qualifier("directWebClientBuilder") WebClient.Builder webClientBuilder,
            ServiceTokenProvider serviceTokenProvider,
            PendingActivationNotificationProperties props) {
        this.webClient = webClientBuilder.build();
        this.serviceTokenProvider = serviceTokenProvider;
        this.props = props;
    }

    public void submit(PendingActivationUserProvisionedEvent event) {
        if (event.email() == null || event.email().isBlank()) {
            return;
        }
        if (props.getAdminEmail() == null || props.getAdminEmail().isBlank()) {
            log.warn("#734 pending-activation notify: no admin recipient configured; skipping (userId={}).",
                    event.userId());
            return;
        }
        try {
            String token = serviceTokenProvider.getToken(props.getTokenAudience(), props.getTokenPermissions());
            Map<String, Object> body = buildIntent(event);
            webClient.post()
                    .uri(props.getBaseUrl() + "/api/v1/internal/notify/intents")
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                    })
                    .body(BodyInserters.fromValue(body))
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofMillis(Math.max(500, props.getTimeoutMillis())));
            log.info("#734 pending-activation admin email submitted for new user id={} email={}",
                    event.userId(), event.email());
        } catch (RuntimeException ex) {
            // Best-effort: never propagate. The admin can still see the passive
            // user in the user list; the email is a convenience.
            log.warn("#734 pending-activation notify failed (non-fatal) for userId={}: {}",
                    event.userId(), ex.getMessage());
        }
    }

    private Map<String, Object> buildIntent(PendingActivationUserProvisionedEvent event) {
        // Stable idempotency key → notification-orchestrator dedups a replay.
        String idempotencyKey = "user-activation:" + event.userId();

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("type", "external");
        recipient.put("email", props.getAdminEmail());
        recipient.put("name", "Admin");
        recipient.put("locale", props.getLocale());

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("templateId", "auth.admin-invite");
        template.put("version", 1);
        template.put("locale", props.getLocale());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("new_user_email", event.email());
        payload.put("new_user_name", event.displayName() == null ? event.email() : event.displayName());
        payload.put("user_id", String.valueOf(event.userId()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intentId", "user-activation-" + UUID.randomUUID());
        body.put("idempotencyKey", idempotencyKey);
        body.put("orgId", props.getOrgId());
        body.put("topicKey", "auth.admin-invite");
        body.put("severity", "info");
        body.put("dataClassification", "security");
        body.put("recipients", List.of(recipient));
        body.put("template", template);
        body.put("channels", List.of("email"));
        body.put("payload", payload);
        return body;
    }
}
