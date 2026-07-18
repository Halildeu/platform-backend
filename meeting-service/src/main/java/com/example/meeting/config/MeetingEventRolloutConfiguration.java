package com.example.meeting.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fails startup when the outbox poller and Redis publisher flags do not match. */
@Configuration(proxyBeanMethods = false)
public class MeetingEventRolloutConfiguration {

    @Bean
    InitializingBean meetingEventRolloutGuard(
            @Value("${meeting.events.redis.enabled:false}") final boolean redisPublisherEnabled,
            @Value("${meeting.events.outbox.poller.enabled:false}") final boolean outboxPollerEnabled) {
        return () -> MeetingEventRolloutGuard.validate(redisPublisherEnabled, outboxPollerEnabled);
    }
}
