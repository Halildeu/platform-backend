package com.example.ethics.notification;

import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.model.NotificationOutbox;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.springframework.stereotype.Component;

/**
 * Builds the notification-orchestrator contract without case or identity data.
 *
 * <p>The template has fixed generic copy. The outbox UUID is an independent delivery
 * identifier; it is not a case/report/message key and cannot be used to join product
 * compartments.
 *
 * <p>ES-301b (#1153): escalation events route by level. Level 1 is the first tier's
 * signal on the new template with {@code level = 1}; levels 2 and above go to the second
 * tier (compliance / board) with {@code severity = warning}. The payload carries exactly
 * the integer level — the orchestrator renders it into fixed copy — and nothing that names
 * a case. Every other event keeps the byte-identical activity intent it always had.
 */
@Component
public class NotificationIntentPayloadFactory {
    static final String TOPIC_KEY = "ethics.case.activity";
    static final String TEMPLATE_ID = "ethics.case.activity";
    static final String ESCALATION_TOPIC_KEY = "ethics.case.escalation";
    static final String ESCALATION_TEMPLATE_ID = "ethics.case.escalated";

    private final NotificationDeliveryProperties properties;

    public NotificationIntentPayloadFactory(NotificationDeliveryProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> build(NotificationOutbox row) {
        String deliveryId = "ethics-" + row.getId();
        OptionalInt level = NotificationOutboxPublisher.escalationLevel(row.getEventType());
        boolean escalation = level.isPresent();
        boolean secondTier = escalation && level.getAsInt() >= 2;

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("type", "subscriber");
        recipient.put("subscriberId", secondTier
                ? properties.getEscalationRecipientSubscriberId()
                : properties.getRecipientSubscriberId());
        recipient.put("locale", properties.getLocale());

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("templateId", escalation ? ESCALATION_TEMPLATE_ID : TEMPLATE_ID);
        template.put("version", 1);
        template.put("locale", properties.getLocale());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intentId", deliveryId);
        body.put("idempotencyKey", deliveryId);
        body.put("orgId", row.getOrgId().toString());
        body.put("topicKey", escalation ? ESCALATION_TOPIC_KEY : TOPIC_KEY);
        body.put("severity", secondTier ? "warning" : "info");
        body.put("dataClassification", "security");
        body.put("recipients", List.of(recipient));
        body.put("template", template);
        body.put("channels", List.of(properties.getChannel()));
        body.put("payload", escalation ? Map.of("level", level.getAsInt()) : Map.of());
        return body;
    }
}
