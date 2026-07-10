package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.service.LiveSttWebSocketProxyHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.client.WebSocketClient;

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

    /** Streaming ON: context must start, and the WebSocketClient type must stay unambiguous. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {ISSUER, DIRECT_STT_ENABLED, TRANSCRIBE_URL,
            STREAMING_ENABLED, STREAM_URL})
    class StreamingEnabled {

        @Autowired
        private ApplicationContext ctx;

        @Autowired
        @Qualifier("directSttWebSocketClient")
        private WebSocketClient qualifiedUpstreamClient;

        @Test
        void contextStartsAndWebSocketClientResolutionStaysUnambiguous() {
            // The regression itself: an unqualified by-type injection point (which is what
            // GatewayAutoConfiguration.websocketRoutingFilter has) must find exactly one
            // candidate. getIfUnique() returns null when the resolution is ambiguous.
            assertThat(ctx.getBeanProvider(WebSocketClient.class).getIfUnique())
                    .as("unqualified WebSocketClient injection must resolve to a single candidate")
                    .isNotNull();

            // Our bridge still gets its own mTLS-capable client through the qualifier,
            // even though it is excluded from default candidate resolution.
            assertThat(qualifiedUpstreamClient).isNotNull();
            assertThat(ctx.getBean(LiveSttWebSocketProxyHandler.class)).isNotNull();
            assertThat(ctx.getBean("liveSttWebSocketHandlerMapping", HandlerMapping.class))
                    .isNotNull();
        }
    }

    /** Streaming OFF (default): bridge beans absent, context still healthy. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {ISSUER, DIRECT_STT_ENABLED, TRANSCRIBE_URL})
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
