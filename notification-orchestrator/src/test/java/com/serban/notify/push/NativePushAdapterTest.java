package com.serban.notify.push;

import com.serban.notify.adapter.ChannelAdapter.DeliveryAttemptResult.Status;
import com.serban.notify.adapter.ChannelAdapterRegistry;
import com.serban.notify.delivery.DeliveryTarget;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativePushAdapterTest {
    private final NativePushRegistry registry = mock(NativePushRegistry.class);
    private final NativePushProviders providers = mock(NativePushProviders.class);
    private final NativePushHttpSender sender = mock(NativePushHttpSender.class);
    private final UUID id = UUID.randomUUID();
    private final DeliveryTarget target = new DeliveryTarget("push", "subscriber", "user", "hash", id.toString(),
        "native-fcm", Map.of("orgId", "org", "eventId", UUID.randomUUID().toString(),
            "meetingId", UUID.randomUUID().toString(), "eventType", "meeting.summary.ready"));
    private final NativePushAdapter adapter = new NativePushAdapter(registry, providers);
    private void registered() {
        when(registry.forDelivery(id, "org", "user")).thenReturn(Optional.of(new NativePushRegistry.SecretTarget(
            new NativePushRegistry.Target(id, "app", "FCM", "TEST"), "private-token", "old-hash")));
        when(providers.sender("app", "FCM", "TEST")).thenReturn(sender);
    }
    @Test void removedRegistrationNeverSends() {
        when(registry.forDelivery(id, "org", "user")).thenReturn(Optional.empty());
        assertEquals(Status.FAILED, adapter.send(target, null).status());
        verifyNoInteractions(providers, sender);
    }
    @Test void invalidTokenDeletesOnlyAttemptedTokenVersion() {
        registered();
        when(sender.send(eq("private-token"), any())).thenReturn(new NativePushHttpSender.Result(NativePushHttpSender.Outcome.INVALID_TOKEN, 404));
        assertEquals(Status.FAILED, adapter.send(target, null).status());
        verify(registry).invalidate(id, "old-hash");
    }
    @Test void transientFailureUsesExistingRetryQueueWithoutDeletingDevice() {
        registered();
        when(sender.send(anyString(), any())).thenReturn(new NativePushHttpSender.Result(NativePushHttpSender.Outcome.RETRY, 503));
        assertEquals(Status.RETRY, adapter.send(target, null).status());
        verify(registry, never()).invalidate(any(), any());
    }
    @Test void providerAcceptanceIsTransportTerminalNotSmsDlr() {
        registered();
        when(sender.send(anyString(), any())).thenReturn(new NativePushHttpSender.Result(NativePushHttpSender.Outcome.PROVIDER_ACCEPTED, 200));
        var result = adapter.send(target, null);
        assertEquals(Status.DELIVERED, result.status());
        assertEquals(true, result.providerMetadata().get("transportAccepted"));
    }
    @Test void routingPreservesBrowserAndOtherChannels() {
        assertEquals("native-push", ChannelAdapterRegistry.dispatchKey("push", "native-fcm"));
        assertEquals("native-push", ChannelAdapterRegistry.dispatchKey("push", "native-apns"));
        assertEquals("push", ChannelAdapterRegistry.dispatchKey("push", "webpush"));
        assertEquals("email", ChannelAdapterRegistry.dispatchKey("email", "native-fcm"));
    }
}
