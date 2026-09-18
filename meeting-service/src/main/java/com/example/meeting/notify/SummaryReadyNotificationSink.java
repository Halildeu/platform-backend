package com.example.meeting.notify;

import com.example.meeting.config.MeetingNotifyProperties;
import com.example.meeting.events.MeetingEventMessage;
import com.example.meeting.service.AssigneeDirectoryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Producer-local delivery; one stable intent per occurrence and canonical recipient. */
@Component
@ConditionalOnProperty(prefix="meeting.notify", name={"enabled", "native-push-enabled"}, havingValue="true")
public class SummaryReadyNotificationSink {
    private final MeetingReadyRecipients recipients;
    private final HttpAssignmentNotificationSink sender;
    @org.springframework.beans.factory.annotation.Autowired
    public SummaryReadyNotificationSink(MeetingReadyRecipients recipients, MeetingNotifyProperties properties,
            NotifyIntentTokenProvider tokens, AssigneeDirectoryClient directory, RestClient.Builder builder, ObjectMapper mapper) {
        this.recipients = recipients;
        this.sender = new HttpAssignmentNotificationSink(properties, tokens, directory, builder, mapper);
    }
    SummaryReadyNotificationSink(MeetingReadyRecipients recipients, HttpAssignmentNotificationSink sender) {
        this.recipients = recipients; this.sender = sender;
    }
    public void deliver(MeetingEventMessage message) {
        if (!"meeting.summary.ready".equals(message.eventType())) return;
        var org = message.orgId() == null ? message.tenantId() : message.orgId();
        for (long user : recipients.resolve(message.meetingId(), message.tenantId(), org)) {
            String key = message.eventKey() + "|native-ready|" + user;
            var body = sender.intent(message, user);
            body.put("intentId", HttpAssignmentNotificationSink.intentId(key));
            body.put("idempotencyKey", key);
            body.put("channels", List.of("push"));
            body.put("payload", Map.of("meetingId", message.meetingId().toString(), "pushAudience", "native"));
            sender.submit(body);
        }
    }
}
