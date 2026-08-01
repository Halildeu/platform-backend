package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves the immutable provider selected when an audio session is created. */
public final class DirectSttProviderRegistry {

    private final Map<String, DirectSttTranscriptionClient> clients;
    private final Set<String> selectableProviderIds;

    public DirectSttProviderRegistry(
            final Collection<DirectSttTranscriptionClient> clients,
            final AudioGatewayProperties.DirectStt config) {
        final Map<String, DirectSttTranscriptionClient> indexed = new LinkedHashMap<>();
        for (final DirectSttTranscriptionClient client : clients) {
            final String id = normalize(client.providerId());
            if (indexed.putIfAbsent(id, client) != null) {
                throw new IllegalStateException("Duplicate Direct-STT provider: " + id);
            }
        }
        this.clients = Map.copyOf(indexed);
        this.selectableProviderIds = config.effectiveSelectableProviders().stream()
                .map(provider -> provider.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        for (final String id : selectableProviderIds) {
            if (!this.clients.containsKey(id)) {
                throw new IllegalStateException("Selectable Direct-STT provider is not wired: " + id);
            }
        }
    }

    public DirectSttTranscriptionClient require(final String providerId) {
        final String id = normalize(providerId);
        if (!selectableProviderIds.contains(id)) {
            throw new IllegalArgumentException("Direct-STT provider is not selectable: " + id);
        }
        final DirectSttTranscriptionClient client = clients.get(id);
        if (client == null) {
            throw new IllegalStateException("Direct-STT provider is not wired: " + id);
        }
        return client;
    }

    public Set<String> selectableProviderIds() {
        return selectableProviderIds;
    }

    private static String normalize(final String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Direct-STT provider is required");
        }
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}
