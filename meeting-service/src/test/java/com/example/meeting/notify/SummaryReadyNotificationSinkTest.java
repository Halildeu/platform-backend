package com.example.meeting.notify;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.meeting.events.MeetingEventMessage;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SummaryReadyNotificationSinkTest {
    @Test void enabledConfigurationCreatesProductionSink() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(SummaryReadyNotificationSink.class)
            .withPropertyValues("meeting.notify.enabled=true", "meeting.notify.native-push-enabled=true")
            .withBean(MeetingReadyRecipients.class, () -> mock(MeetingReadyRecipients.class))
            .withBean(com.example.meeting.config.MeetingNotifyProperties.class)
            .withBean(NotifyIntentTokenProvider.class, () -> mock(NotifyIntentTokenProvider.class))
            .withBean(com.example.meeting.service.AssigneeDirectoryClient.class, () -> mock(com.example.meeting.service.AssigneeDirectoryClient.class))
            .withBean(org.springframework.web.client.RestClient.Builder.class, org.springframework.web.client.RestClient::builder)
            .withBean(com.fasterxml.jackson.databind.ObjectMapper.class)
            .run(context -> assertThat(context).hasSingleBean(SummaryReadyNotificationSink.class));
    }
    @Test void retryWithChangedAudienceKeepsPerRecipientIdentity() {
        var recipients = mock(MeetingReadyRecipients.class);
        var sender = mock(HttpAssignmentNotificationSink.class);
        var id = UUID.randomUUID(); var org = UUID.randomUUID();
        var event = new MeetingEventMessage("occurrence", "meeting.summary.ready", "meeting", id, 1, id, org, org, "{}");
        when(recipients.resolve(id, org, org)).thenReturn(List.of(1L, 2L), List.of(2L, 3L));
        when(sender.intent(eq(event), anyLong())).thenAnswer(call -> new LinkedHashMap<String,Object>());
        var sink = new SummaryReadyNotificationSink(recipients, sender);
        sink.deliver(event); sink.deliver(event);
        ArgumentCaptor<Map<String,Object>> capture = ArgumentCaptor.forClass(Map.class);
        verify(sender, times(4)).submit(capture.capture());
        var bodies = capture.getAllValues();
        assertThat(bodies.get(1)).isEqualTo(bodies.get(2));
        assertThat(bodies.get(0).get("intentId")).isNotEqualTo(bodies.get(3).get("intentId"));
        assertThat(bodies.get(0).get("payload")).isEqualTo(Map.of("meetingId", id.toString(), "pushAudience", "native"));
    }
}
