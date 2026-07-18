package com.example.audiogateway.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.reactivestreams.Publisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class LiveSttWebSocketProxyHandlerTest {

    private AudioSessionRegistry sessions;
    private AudioGatewayAuditSink auditSink;
    private WebSocketClient upstreamClient;
    private LiveSttWebSocketProxyHandler handler;

    @BeforeEach
    void setUp() {
        sessions = mock(AudioSessionRegistry.class);
        auditSink = mock(AudioGatewayAuditSink.class);
        upstreamClient = mock(WebSocketClient.class);

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl("ws://live-stt:8200/ws/stream");
        handler = new LiveSttWebSocketProxyHandler(
                sessions,
                properties,
                auditSink,
                DirectSttTranscriptResultSink.noop(),
                upstreamClient,
                new ObjectMapper(),
                new SimpleMeterRegistry());
    }

    @Test
    void rejectsMissingTenantClaimBeforeOpeningUpstream() {
        final WebSocketSession client = clientSession(jwt(false, true));

        handler.handle(client).block();

        verify(client).close(CloseStatus.POLICY_VIOLATION);
        verify(upstreamClient, never()).execute(any(URI.class), any(WebSocketHandler.class));
    }

    @Test
    void rejectsForeignSessionWithoutOpeningUpstream() {
        final WebSocketSession client = clientSession(jwt(true, true));
        when(sessions.get("session-1")).thenReturn(Optional.of(session(2L, 4L, SessionState.STARTED)));

        handler.handle(client).block();

        verify(client).close(CloseStatus.POLICY_VIOLATION);
        verify(upstreamClient, never()).execute(any(URI.class), any(WebSocketHandler.class));
    }

    @Test
    void opensUpstreamForAuthenticatedSessionOwner() {
        final WebSocketSession client = clientSession(jwt(true, true));
        when(sessions.get("session-1")).thenReturn(Optional.of(session(1L, 4L, SessionState.STARTED)));
        when(upstreamClient.execute(any(URI.class), any(WebSocketHandler.class)))
                .thenReturn(Mono.empty());

        handler.handle(client).block();

        verify(upstreamClient).execute(
                eq(URI.create("ws://live-stt:8200/ws/stream")),
                any(WebSocketHandler.class));
        verify(client, never()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void extractsOnlyCanonicalSessionStreamPath() {
        final WebSocketSession canonical = mock(WebSocketSession.class);
        when(canonical.getHandshakeInfo()).thenReturn(handshake(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                Mono.empty()));
        final WebSocketSession wrong = mock(WebSocketSession.class);
        when(wrong.getHandshakeInfo()).thenReturn(handshake(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/status"),
                Mono.empty()));

        org.assertj.core.api.Assertions.assertThat(
                        LiveSttWebSocketProxyHandler.sessionId(canonical))
                .isEqualTo("session-1");
        org.assertj.core.api.Assertions.assertThat(
                        LiveSttWebSocketProxyHandler.sessionId(wrong))
                .isEmpty();
    }

    @Test
    void closesBadDataForClientTextFrameWithoutCountingUpstreamFailure() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        handler = new LiveSttWebSocketProxyHandler(
                sessions,
                configuredProperties(),
                auditSink,
                DirectSttTranscriptResultSink.noop(),
                upstreamClient,
                new ObjectMapper(),
                meters);
        final WebSocketSession client = clientSession(jwt(true, true));
        final WebSocketSession upstream = mock(WebSocketSession.class);
        when(sessions.get("session-1")).thenReturn(Optional.of(session(1L, 4L, SessionState.STARTED)));
        when(client.receive()).thenReturn(Flux.just(textMessage("invalid")));
        when(client.send(any(Publisher.class))).thenReturn(Mono.never());
        when(upstream.receive()).thenReturn(Flux.never());
        when(upstream.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<org.springframework.web.reactive.socket.WebSocketMessage>>
                                getArgument(0))
                        .then());
        when(upstreamClient.execute(any(URI.class), any(WebSocketHandler.class)))
                .thenAnswer(invocation ->
                        invocation.<WebSocketHandler>getArgument(1).handle(upstream));

        handler.handle(client).block();

        verify(client).close(CloseStatus.BAD_DATA);
        org.assertj.core.api.Assertions.assertThat(
                        meters.counter("audio_gateway_live_stream_upstream_failures_total").count())
                .isZero();
    }

    private WebSocketSession clientSession(final JwtAuthenticationToken authentication) {
        final WebSocketSession session = mock(WebSocketSession.class);
        when(session.getHandshakeInfo()).thenReturn(handshake(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                Mono.just(authentication)));
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        return session;
    }

    private static HandshakeInfo handshake(
            final URI uri,
            final Mono<java.security.Principal> principal) {
        return new HandshakeInfo(uri, new HttpHeaders(), principal, null);
    }

    private static JwtAuthenticationToken jwt(
            final boolean tenantClaim,
            final boolean userClaim) {
        final Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("subject-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (tenantClaim) {
            builder.claim("companyId", 1L);
        }
        if (userClaim) {
            builder.claim("userId", 4L);
        }
        return new JwtAuthenticationToken(builder.build());
    }

    private static org.springframework.web.reactive.socket.WebSocketMessage textMessage(
            final String value) {
        return new org.springframework.web.reactive.socket.WebSocketMessage(
                org.springframework.web.reactive.socket.WebSocketMessage.Type.TEXT,
                DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static AudioGatewayProperties configuredProperties() {
        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl("ws://live-stt:8200/ws/stream");
        return properties;
    }

    private static SessionRecord session(
            final Long tenantId,
            final Long userId,
            final SessionState state) {
        return new SessionRecord(
                "session-1",
                tenantId,
                userId,
                "meeting-1",
                "device-1",
                "tr",
                AudioFormat.PCM16,
                16_000,
                1,
                "start-key-123456",
                1_000L,
                state,
                -1L,
                0L,
                0L,
                null,
                null,
                null,
                0L,
                1_000L);
    }
}
