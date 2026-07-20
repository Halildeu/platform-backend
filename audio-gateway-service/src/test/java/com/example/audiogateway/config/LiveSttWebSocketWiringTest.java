package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.service.LiveAudioStreamFrame;
import com.example.audiogateway.service.LiveSttWebSocketProxyHandler;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

/**
 * Boots the real application context with the Faz 24 issue #184 live WebSocket bridge
 * enabled — the check that was missing when the bridge shipped.
 *
 * <p>{@link LiveSttWebSocketConfigTest} constructs {@link LiveSttWebSocketConfig} directly
 * with {@code new}, so it can prove frame-limit alignment but can never observe bean
 * resolution. That blind spot let a startup-fatal defect ship behind a default-off flag:
 * with {@code streaming.enabled=true} the unqualified {@link WebSocketClient} bean clashed
 * with Spring Cloud Gateway's {@code reactorNettyWebSocketClient} at
 * {@code GatewayAutoConfiguration.websocketRoutingFilter}, and the pod crash-looped with
 * "required a single bean, but 2 were found" (live evidence: k3d-test
 * audio-gateway-76b5457bb6-lgvj9, exitCode 1, restarts 3).
 *
 * <p>No live live-stt is needed: the upstream URL is only parsed at bean construction; the
 * connection is opened per session.
 */
class LiveSttWebSocketWiringTest {

    private static final String ISSUER =
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test";
    private static final String DIRECT_STT_ENABLED = "audio.gateway.direct-stt.enabled=true";
    private static final String TRANSCRIBE_URL =
            "audio.gateway.direct-stt.transcribe-url=http://live-stt:8200/transcribe";
    private static final String STREAMING_ENABLED =
            "audio.gateway.direct-stt.streaming.enabled=true";
    private static final String STREAM_URL =
            "audio.gateway.direct-stt.streaming.stream-url=ws://live-stt:8200/ws/stream";
    private static final String RESULT_STREAM_ENABLED =
            "audio.gateway.direct-stt.transcript-result-stream.enabled=true";

    /** Streaming ON: context must start, and the WebSocketClient type must stay unambiguous. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {ISSUER, DIRECT_STT_ENABLED, TRANSCRIBE_URL,
            STREAMING_ENABLED, STREAM_URL, RESULT_STREAM_ENABLED})
    class StreamingEnabled {

        @Autowired
        private ApplicationContext ctx;

        @Autowired
        @Qualifier("directSttWebSocketClient")
        private WebSocketClient qualifiedUpstreamClient;

        @Test
        void contextStartsAndTheGatewayKeepsItsOwnWebSocketClient() {
            final WebSocketClient gatewayClient =
                    ctx.getBean("reactorNettyWebSocketClient", WebSocketClient.class);
            final WebSocketClient bridgeClient =
                    ctx.getBean("directSttWebSocketClient", WebSocketClient.class);

            // The regression itself: an unqualified by-type injection point (which is what
            // GatewayAutoConfiguration.websocketRoutingFilter has) must resolve, and it must
            // resolve to the GATEWAY's client. Asserting only isNotNull() would stay green if
            // the bridge bean were ever marked @Primary — the gateway would then silently
            // route through our mTLS client.
            assertThat(ctx.getBeanProvider(WebSocketClient.class).getIfUnique())
                    .as("unqualified WebSocketClient injection must resolve to the gateway client")
                    .isSameAs(gatewayClient);

            // Our bridge still gets its own mTLS-capable client through the qualifier, and the
            // two uses never collapse onto the same instance.
            assertThat(qualifiedUpstreamClient)
                    .isSameAs(bridgeClient)
                    .isNotSameAs(gatewayClient);

            assertThat(ctx.getBean(LiveSttWebSocketProxyHandler.class)).isNotNull();
            assertThat(ctx.getBean("liveSttWebSocketHandlerMapping", HandlerMapping.class))
                    .isNotNull();
        }

        /**
         * Codex 2026-07-20 REVISE: the ready-event log line advertises a path that MUST
         * match what {@link org.springframework.web.reactive.handler.SimpleUrlHandlerMapping}
         * actually registers. If the two ever diverge, operators would see a log for one
         * route while the WebSocket handler answers another — an observability lie. Pin
         * the single-source-of-truth constant against the live registration.
         */
        @Test
        void announcedRoutePatternIsTheOneActuallyMappedInHandlerMapping() {
            final org.springframework.web.reactive.handler.SimpleUrlHandlerMapping mapping =
                    (org.springframework.web.reactive.handler.SimpleUrlHandlerMapping)
                            ctx.getBean("liveSttWebSocketHandlerMapping", HandlerMapping.class);

            assertThat(mapping.getUrlMap().keySet())
                    .as("bridge route must be registered under the shared constant")
                    .containsExactly(LiveSttWebSocketConfig.BRIDGE_ROUTE_PATTERN);
        }

        @Test
        void bridgeHandlerAdapterOutranksTheFrameworkOneSoFrameBoundsActuallyApply() {
            // No startup ambiguity here (DispatcherHandler collects ALL HandlerAdapter beans
            // and picks the first that supports the handler), but the selection is order-driven
            // and therefore silent: if the framework adapter ever outranked ours, the context
            // would still start while our configured server-side maxFramePayloadLength
            // quietly stopped applying. Pin the ordering invariant, not just startup health.
            final WebSocketHandlerAdapter bridgeAdapter =
                    ctx.getBean("webSocketHandlerAdapter", WebSocketHandlerAdapter.class);

            final List<WebSocketHandlerAdapter> supporting =
                    ctx.getBeansOfType(WebSocketHandlerAdapter.class).values().stream()
                            .sorted(AnnotationAwareOrderComparator.INSTANCE)
                            .toList();

            assertThat(supporting)
                    .as("bridge WebSocketHandlerAdapter must be selected before any framework one")
                    .isNotEmpty()
                    .first()
                    .isSameAs(bridgeAdapter);

            final HandshakeWebSocketService service =
                    (HandshakeWebSocketService) bridgeAdapter.getWebSocketService();
            final ReactorNettyRequestUpgradeStrategy strategy =
                    (ReactorNettyRequestUpgradeStrategy) service.getUpgradeStrategy();
            final int configuredMaxFrameBytes = ctx.getBean(AudioGatewayProperties.class)
                    .getDirectStt().getStreaming().getMaxFrameBytes();
            assertThat(strategy.getWebsocketServerSpec().maxFramePayloadLength())
                    .isEqualTo(configuredMaxFrameBytes + LiveAudioStreamFrame.HEADER_BYTES);
        }
    }

    /** Streaming OFF (default): bridge beans absent, context still healthy. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
            ISSUER, DIRECT_STT_ENABLED, TRANSCRIBE_URL, RESULT_STREAM_ENABLED})
    class StreamingDisabled {

        @Autowired
        private ApplicationContext ctx;

        @Test
        void bridgeBeansAreAbsentAndContextStarts() {
            assertThat(ctx.getBeanNamesForType(LiveSttWebSocketProxyHandler.class)).isEmpty();
            assertThat(ctx.containsBean("directSttWebSocketClient")).isFalse();
        }
    }
}
