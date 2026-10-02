package com.serban.notify.push;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.redaction.PiiRedactor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "notify.native-push.sender-enabled", havingValue = "true")
public class NativePushPlanner {
    private final NativePushRegistry registry;
    private final PiiRedactor redactor;
    public NativePushPlanner(NativePushRegistry registry, PiiRedactor redactor) {
        this.registry = registry; this.redactor = redactor;
    }
    public List<DeliveryTarget> plan(NotificationIntent intent, String subscriber) {
        NativePushHttpSender.MeetingEvent event;
        try {
            event = new NativePushHttpSender.MeetingEvent(
                UUID.nameUUIDFromBytes(intent.getIntentId().getBytes(StandardCharsets.UTF_8)),
                UUID.fromString((String) intent.getPayload().get("meetingId")), intent.getTopicKey());
        } catch (IllegalArgumentException | NullPointerException | ClassCastException invalid) { return List.of(); }
        Map<String, Object> metadata = Map.of("orgId", intent.getOrgId(), "meetingId", event.meetingId().toString(),
            "eventId", event.eventId().toString(), "eventType", event.eventType());
        return registry.targets(intent.getOrgId(), subscriber).stream().map(device -> new DeliveryTarget(
            "push", "subscriber", subscriber,
            redactor.hashRecipient(intent.getOrgId(), "native_push", subscriber + "|" + device.id()),
            device.id().toString(), "native-" + device.provider().toLowerCase(java.util.Locale.ROOT), metadata)).toList();
    }
}
