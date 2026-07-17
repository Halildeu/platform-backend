package com.example.transcript.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TranscriptEventRolloutConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TranscriptEventRolloutConfiguration.class);

    @Test
    void contextStartsWithDefaultDisabledPair() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void contextStartsWithEnabledPair() {
        runner.withPropertyValues(
                        "transcript.events.redis.enabled=true",
                        "transcript.events.outbox.poller.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void contextFailsWhenOnlyPollerIsEnabled() {
        runner.withPropertyValues(
                        "transcript.events.redis.enabled=false",
                        "transcript.events.outbox.poller.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(TranscriptEventRolloutGuard.ERROR_MESSAGE);
                });
    }

    @Test
    void contextFailsWhenOnlyRedisPublisherIsEnabled() {
        runner.withPropertyValues(
                        "transcript.events.redis.enabled=true",
                        "transcript.events.outbox.poller.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage(TranscriptEventRolloutGuard.ERROR_MESSAGE);
                });
    }
}
