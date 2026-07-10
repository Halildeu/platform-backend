package com.example.audiogateway.config;

import com.example.audiogateway.service.AudioGatewayAuditSink;
import com.example.audiogateway.service.AudioSessionRegistry;
import com.example.audiogateway.service.LiveAudioStreamFrame;
import com.example.audiogateway.service.LiveSttWebSocketProxyHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.netty.http.server.WebsocketServerSpec;

/**
 * Default-off #184 gateway-to-live-stt WebSocket bridge wiring.
 */
@Configuration
@ConditionalOnProperty(
        name = "audio.gateway.direct-stt.streaming.enabled",
        havingValue = "true")
public class LiveSttWebSocketConfig {

    /**
     * Upstream client for the gateway-to-live-stt bridge.
     *
     * <p>{@code defaultCandidate = false} is load-bearing: this service also has
     * Spring Cloud Gateway on the classpath, whose
     * {@code GatewayAutoConfiguration.websocketRoutingFilter} injects a
     * {@link WebSocketClient} <em>by type, without a qualifier</em>. Registering a second
     * unqualified candidate made that injection point ambiguous and the application
     * failed to start ("required a single bean, but 2 were found") the moment
     * {@code streaming.enabled=true} — invisible while the bridge was default-off.
     * Excluding this bean from default (type-only) candidate resolution leaves the
     * gateway's own client as the unique candidate, while our own
     * {@code @Qualifier("directSttWebSocketClient")} injection still resolves it.
     *
     * <p>Machine-enforced by {@code LiveSttWebSocketWiringTest}, which boots the real
     * context with streaming enabled.
     */
    @Bean(name = "directSttWebSocketClient", defaultCandidate = false)
    public WebSocketClient directSttWebSocketClient(
            final AudioGatewayProperties properties) throws SSLException {
        final AudioGatewayProperties.DirectStt direct = properties.getDirectStt();
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) direct.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(direct.getResponseTimeoutMs()));
        if (direct.getTls().isEnabled()) {
            client = DirectSttConfig.applyMutualTls(client, direct.getTls());
        }
        return new ReactorNettyWebSocketClient(
                client,
                () -> WebsocketClientSpec.builder()
                        .maxFramePayloadLength(direct.getMaxResponseBytes()));
    }

    @Bean
    public LiveSttWebSocketProxyHandler liveSttWebSocketProxyHandler(
            final AudioSessionRegistry sessions,
            final AudioGatewayProperties properties,
            final AudioGatewayAuditSink auditSink,
            @Qualifier("directSttWebSocketClient") final WebSocketClient upstreamClient,
            final MeterRegistry meters) {
        return new LiveSttWebSocketProxyHandler(
                sessions, properties, auditSink, upstreamClient, meters);
    }

    @Bean
    public HandlerMapping liveSttWebSocketHandlerMapping(
            final LiveSttWebSocketProxyHandler handler) {
        final Map<String, WebSocketHandler> mappings = Map.of(
                "/api/v1/audio-gateway/sessions/*/stream", handler);
        final SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(-2);
        mapping.setUrlMap(mappings);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter(
            final AudioGatewayProperties properties) {
        final ReactorNettyRequestUpgradeStrategy upgradeStrategy =
                new ReactorNettyRequestUpgradeStrategy(
                        () -> WebsocketServerSpec.builder()
                                .maxFramePayloadLength(
                                        properties.getDirectStt().getStreaming().getMaxFrameBytes()
                                                + LiveAudioStreamFrame.HEADER_BYTES));
        return new WebSocketHandlerAdapter(
                new HandshakeWebSocketService(upgradeStrategy));
    }
}
