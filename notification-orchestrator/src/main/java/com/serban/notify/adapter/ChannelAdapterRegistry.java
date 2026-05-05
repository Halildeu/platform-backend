package com.serban.notify.adapter;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ChannelAdapterRegistry — Spring auto-discovery of {@link ChannelAdapter} beans.
 *
 * <p>Each implementation registered as bean ({@code @Component} on adapter class);
 * Spring constructor injection collects {@code List<ChannelAdapter>}, this class
 * indexes by {@link ChannelAdapter#channelKey()}.
 */
@Component
public class ChannelAdapterRegistry {

    private final Map<String, ChannelAdapter> byChannel;

    public ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        this.byChannel = adapters.stream()
            .collect(Collectors.toUnmodifiableMap(
                ChannelAdapter::channelKey,
                a -> a,
                (a, b) -> {
                    throw new IllegalStateException(
                        "duplicate ChannelAdapter for channel '" + a.channelKey() + "'"
                    );
                }
            ));
    }

    public Optional<ChannelAdapter> get(String channelKey) {
        return Optional.ofNullable(byChannel.get(channelKey));
    }

    public boolean supports(String channelKey) {
        return byChannel.containsKey(channelKey);
    }

    public java.util.Set<String> supportedChannels() {
        return byChannel.keySet();
    }
}
