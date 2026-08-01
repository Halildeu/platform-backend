package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.TranscriptResult;

import java.util.List;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

class DirectSttProviderRegistryTest {

    @Test
    void resolvesOnlyExplicitlySelectableProviders() {
        final AudioGatewayProperties.DirectStt config = new AudioGatewayProperties.DirectStt();
        config.setSelectableProviders(java.util.Set.of(
                AudioGatewayProperties.DirectStt.Provider.INTERNAL,
                AudioGatewayProperties.DirectStt.Provider.SPEECHMATICS));
        final DirectSttProviderRegistry registry = new DirectSttProviderRegistry(
                List.of(client("internal"), client("speechmatics")), config);

        assertThat(registry.require("internal").providerId()).isEqualTo("internal");
        assertThat(registry.require("speechmatics").providerId()).isEqualTo("speechmatics");
        assertThatThrownBy(() -> registry.require("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyEmptySelectionExposesOnlyConfiguredDefault() {
        final AudioGatewayProperties.DirectStt config = new AudioGatewayProperties.DirectStt();
        final DirectSttProviderRegistry registry = new DirectSttProviderRegistry(
                List.of(client("internal"), client("speechmatics")), config);

        assertThat(registry.selectableProviderIds()).containsExactly("internal");
        assertThatThrownBy(() -> registry.require("speechmatics"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DirectSttTranscriptionClient client(final String id) {
        return new DirectSttTranscriptionClient() {
            @Override
            public Mono<TranscriptResult> transcribe(final DirectSttTranscriptionRequest request) {
                return Mono.empty();
            }

            @Override
            public String providerId() {
                return id;
            }
        };
    }
}
