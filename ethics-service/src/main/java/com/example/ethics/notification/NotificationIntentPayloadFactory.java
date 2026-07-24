package com.example.ethics.notification;

import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.model.NotificationOutbox;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds the notification-orchestrator contract without case or identity data.
 *
 * <p>The template has fixed generic copy and the payload is empty. The outbox
 * UUID is an independent delivery identifier; it is not a case/report/message
 * key and cannot be used to join product compartments.
 */
@Component
public class NotificationIntentPayloadFactory {
    static final String TOPIC_KEY = "ethics.case.activity";
    static final String TEMPLATE_ID = "ethics.case.activity";

    private final NotificationDeliveryProperties properties;

    public NotificationIntentPayloadFactory(NotificationDeliveryProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> build(NotificationOutbox row) {
        String deliveryId = "ethics-" + row.getId();

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("type", "subscriber");
        recipient.put("subscriberId", properties.getRecipientSubscriberId());
        recipient.put("locale", properties.getLocale());

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("templateId", TEMPLATE_ID);
        template.put("version", 1);
        template.put("locale", properties.getLocale());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intentId", deliveryId);
        body.put("idempotencyKey", deliveryId);
        body.put("orgId", row.getOrgId().toString());
        body.put("topicKey", TOPIC_KEY);
        body.put("severity", "info");
        body.put("dataClassification", "security");
        body.put("recipients", List.of(recipient));
        body.put("template", template);
        body.put("channels", List.of(properties.getChannel()));
        body.put("payload", Map.of());
        return body;
    }
}
