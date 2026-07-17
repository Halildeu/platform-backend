package com.example.transcript.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fails startup when the outbox poller cannot use the configured event transport. */
@Configuration(proxyBeanMethods = false)
public class TranscriptEventRolloutConfiguration {

    @Bean
    InitializingBean transcriptEventRolloutGuard(
            @Value("${transcript.events.redis.enabled:false}") boolean redisPublisherEnabled,
            @Value("${transcript.events.outbox.poller.enabled:false}") boolean outboxPollerEnabled) {
        return () -> TranscriptEventRolloutGuard.validate(
                redisPublisherEnabled, outboxPollerEnabled);
    }
}
