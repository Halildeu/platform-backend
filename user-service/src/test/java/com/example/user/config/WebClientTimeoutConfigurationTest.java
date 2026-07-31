package com.example.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins the response timeout that {@link WebClientConfig} gives every outbound call.
 *
 * <p>Why this test exists: {@code GET /api/v1/users} blocks on permission-service
 * {@code /authz/me}, which calls back into user-service to resolve the principal. Measured on the
 * live cell 2026-07-28 that round trip peaked at 5.029s while every component was idle — just past
 * the old hardcoded 5s ceiling — so the admin user list answered 500 instead of a slow 200. A
 * silent revert to 5s would bring that failure back with no other signal, so the default is
 * asserted here rather than left to review.
 *
 * <p>This does not claim the nested hop is correct; removing it is tracked separately. It claims
 * only that a slow-but-working chain must not be reported as a failure.
 */
class WebClientTimeoutConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebClientAutoConfiguration.class))
            .withUserConfiguration(WebClientConfig.class);

    @Test
    void defaultResponseTimeoutLeavesHeadroomOverTheMeasuredNestedHop() {
        // 8s after gitops#3210: the carrier-starvation stall that forced 30s is
        // gone (by-email 14.3s -> 54ms), and a 30s ceiling holds resources long
        // enough to turn one slow downstream into a queue.
        runner.run(context -> {
            WebClientConfig config = context.getBean(WebClientConfig.class);
            Object seconds = ReflectionTestUtils.getField(config, "responseTimeoutSeconds");

            assertThat(seconds)
                    .as("default response timeout; still ~150x the 54ms measured peak")
                    .isEqualTo(8L);
        });
    }

    @Test
    void defaultConnectTimeoutIsUnchanged() {
        runner.run(context -> {
            WebClientConfig config = context.getBean(WebClientConfig.class);

            assertThat(ReflectionTestUtils.getField(config, "connectTimeoutMillis"))
                    .as("connect timeout is a separate concern and was deliberately not raised")
                    .isEqualTo(3_000);
        });
    }

    @Test
    void bothTimeoutsRemainOperatorOverridable() {
        runner.withPropertyValues(
                        "user.webclient.response-timeout-seconds=7",
                        "user.webclient.connect-timeout-millis=1500")
                .run(context -> {
                    WebClientConfig config = context.getBean(WebClientConfig.class);

                    assertThat(ReflectionTestUtils.getField(config, "responseTimeoutSeconds"))
                            .isEqualTo(7L);
                    assertThat(ReflectionTestUtils.getField(config, "connectTimeoutMillis"))
                            .isEqualTo(1500);
                });
    }

    @Test
    void bothBuilderQualifiersAreStillExposed() {
        runner.run(context -> assertThat(context)
                .hasBean("plainWebClientBuilder")
                .hasBean("directWebClientBuilder"));
    }
}
