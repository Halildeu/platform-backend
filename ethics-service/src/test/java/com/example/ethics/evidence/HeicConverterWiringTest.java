package com.example.ethics.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * ES-104K (#2930) — exactly one {@link HeicConverter} exists in every configuration.
 *
 * <p>Pins the 2026-08-01 live crash-loop: {@code @ConditionalOnProperty(havingValue = "")}
 * means "any value but false", not "empty", so a configured URL produced BOTH beans and
 * the worker died at startup. The two conditions are now one negated expression; this
 * test holds them together.
 */
class HeicConverterWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withBean(com.example.ethics.config.EvidenceProperties.class)
            .withUserConfiguration(DisabledHeicConverter.class, HttpHeicConverter.class);

    @Test
    @DisplayName("URL yokken tek bean: fail-closed Disabled")
    void withoutAUrlOnlyTheDisabledConverterExists() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(HeicConverter.class);
            assertThat(context.getBean(HeicConverter.class))
                    .isInstanceOf(DisabledHeicConverter.class);
        });
    }

    @Test
    @DisplayName("URL varken tek bean: Http — canlı çöküşün pinlenmesi")
    void withAUrlOnlyTheHttpConverterExists() {
        runner.withPropertyValues(
                        "ethics.evidence.processor.heic-converter-url=http://heic-converter:9000")
                .run(context -> {
                    assertThat(context).hasSingleBean(HeicConverter.class);
                    assertThat(context.getBean(HeicConverter.class))
                            .isInstanceOf(HttpHeicConverter.class);
                });
    }
}
