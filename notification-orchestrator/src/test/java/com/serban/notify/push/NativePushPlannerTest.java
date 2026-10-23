package com.serban.notify.push;

import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.redaction.PiiRedactor;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativePushPlannerTest {
    @Test void usesOnlyCurrentOwnersDevicesAndStableEventIdentity() {
        var registry = mock(NativePushRegistry.class);
        var redactor = mock(PiiRedactor.class);
        var intent = mock(NotificationIntent.class);
        when(intent.getIntentId()).thenReturn("intent-1"); when(intent.getOrgId()).thenReturn("org");
        when(intent.getTopicKey()).thenReturn("meeting.summary.ready");
        when(intent.getPayload()).thenReturn(Map.of("meetingId", UUID.randomUUID().toString(), "transcript", "private"));
        when(registry.targets("org", "user")).thenReturn(List.of(new NativePushRegistry.Target(UUID.randomUUID(), "app", "FCM", "TEST")));
        var planner = new NativePushPlanner(registry, redactor);
        var first = planner.plan(intent, "user");
        assertEquals(1, first.size()); assertEquals("native-fcm", first.getFirst().providerKey());
        assertEquals(first.getFirst().routingMetadata(), planner.plan(intent, "user").getFirst().routingMetadata());
        assertFalse(first.getFirst().routingMetadata().containsKey("transcript"));
        when(intent.getTopicKey()).thenReturn("other.topic");
        assertTrue(planner.plan(intent, "user").isEmpty());
    }
}
