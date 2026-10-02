package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class SpeechmaticsPunctuationBindingTest {

    @Test
    void deploymentVariableReachesProviderConfiguration() throws IOException {
        assertThat(bind(Map.of("AUDIO_GATEWAY_SPEECHMATICS_PUNCTUATION_SENSITIVITY", "0.25"))
                .getPunctuationSensitivity()).isEqualTo(0.25d);
    }

    @Test
    void absentDeploymentVariablePreservesProviderDefault() throws IOException {
        assertThat(bind(Map.of()).getPunctuationSensitivity()).isNull();
    }

    private AudioGatewayProperties.DirectStt.Speechmatics bind(final Map<String, Object> variables)
            throws IOException {
        final MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("deployment", variables));
        for (final var source : new YamlPropertySourceLoader()
                .load("k8s", new ClassPathResource("application-k8s.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("audio.gateway.direct-stt.speechmatics",
                Bindable.of(AudioGatewayProperties.DirectStt.Speechmatics.class)).get();
    }
}
