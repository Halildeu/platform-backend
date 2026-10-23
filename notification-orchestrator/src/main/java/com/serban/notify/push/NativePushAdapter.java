package com.serban.notify.push;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "notify.native-push.sender-enabled", havingValue = "true")
public class NativePushAdapter implements ChannelAdapter {
    private final NativePushRegistry registry;
    private final NativePushProviders providers;
    public NativePushAdapter(NativePushRegistry registry, NativePushProviders providers) {
        this.registry = registry; this.providers = providers;
    }
    public String channelKey() { return "native-push"; }
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage ignored) {
        try {
            if (!"subscriber".equals(target.recipientType())) return DeliveryAttemptResult.failed("native_target_invalid", null);
            var metadata = target.routingMetadata();
            var registration = registry.forDelivery(UUID.fromString(target.targetRef()),
                (String) metadata.get("orgId"), target.recipientId());
            if (registration.isEmpty()) return DeliveryAttemptResult.failed("native_registration_removed", null);
            var secret = registration.get();
            var device = secret.target();
            var sender = providers.sender(device.app(), device.provider(), device.environment());
            if (sender == null) return DeliveryAttemptResult.failed("native_provider_not_configured", null);
            var event = new NativePushHttpSender.MeetingEvent(UUID.fromString((String) metadata.get("eventId")),
                UUID.fromString((String) metadata.get("meetingId")), (String) metadata.get("eventType"));
            var result = sender.send(secret.token(), event);
            return switch (result.outcome()) {
                // Same transport-terminal semantics as WebPush; not proof of phone receipt.
                case PROVIDER_ACCEPTED -> new DeliveryAttemptResult(DeliveryAttemptResult.Status.DELIVERED,
                    event.eventId().toString(), null, result.httpStatus(), null, Map.of("transportAccepted", true));
                case RETRY -> new DeliveryAttemptResult(DeliveryAttemptResult.Status.RETRY, null,
                    "native_provider_retry", result.httpStatus(), null, Map.of("retryAfterSeconds", result.retryAfterSeconds()));
                case INVALID_TOKEN -> {
                    registry.invalidate(device.id(), secret.tokenHash());
                    yield DeliveryAttemptResult.failed("native_token_invalid", result.httpStatus());
                }
                case CONFIGURATION_ERROR -> DeliveryAttemptResult.failed("native_provider_configuration", result.httpStatus());
                case REJECTED -> DeliveryAttemptResult.failed("native_provider_rejected", result.httpStatus());
            };
        } catch (IllegalArgumentException | NullPointerException | ClassCastException invalid) {
            return DeliveryAttemptResult.failed("native_target_invalid", null);
        }
    }
}
