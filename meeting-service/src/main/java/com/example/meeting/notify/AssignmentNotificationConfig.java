package com.example.meeting.notify;

import com.example.meeting.config.MeetingNotifyProperties;
import com.example.meeting.service.AssigneeDirectoryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Default-off wiring: the HTTP sink exists only when {@code meeting.notify.enabled=true};
 * otherwise the outbox poller gets the no-op sink and behaves exactly as before.
 */
@Configuration
public class AssignmentNotificationConfig {

    @Bean
    @ConditionalOnProperty(prefix = "meeting.notify", name = "enabled", havingValue = "true")
    AssignmentNotificationSink httpAssignmentNotificationSink(
            MeetingNotifyProperties properties,
            NotifyIntentTokenProvider tokens,
            AssigneeDirectoryClient directory,
            RestClient.Builder builder,
            ObjectMapper objectMapper) {
        return new HttpAssignmentNotificationSink(properties, tokens, directory, builder, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "meeting.notify", name = "enabled", havingValue = "false", matchIfMissing = true)
    AssignmentNotificationSink noopAssignmentNotificationSink() {
        return AssignmentNotificationSink.NOOP;
    }
}
