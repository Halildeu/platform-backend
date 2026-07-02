package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.service.LiveAudioStreamFrame;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

class LiveSttWebSocketConfigTest {

    @Test
    void alignsClientAndServerFrameLimitsWithConfiguredBounds() throws SSLException {
        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().setEnabled(true);
        properties.getDirectStt().setTranscribeUrl("http://live-stt:8200/transcribe");
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl("ws://live-stt:8200/ws/stream");
        properties.getDirectStt().getStreaming().setMaxFrameBytes(32_000);
        properties.getDirectStt().setMaxResponseBytes(48_000);

        final LiveSttWebSocketConfig config = new LiveSttWebSocketConfig();
        final ReactorNettyWebSocketClient client =
                (ReactorNettyWebSocketClient) config.directSttWebSocketClient(properties);
        final WebSocketHandlerAdapter adapter = config.webSocketHandlerAdapter(properties);
        final HandshakeWebSocketService service =
                (HandshakeWebSocketService) adapter.getWebSocketService();
        final ReactorNettyRequestUpgradeStrategy strategy =
                (ReactorNettyRequestUpgradeStrategy) service.getUpgradeStrategy();

        assertThat(client.getWebsocketClientSpec().maxFramePayloadLength()).isEqualTo(48_000);
        assertThat(strategy.getWebsocketServerSpec().maxFramePayloadLength())
                .isEqualTo(32_000 + LiveAudioStreamFrame.HEADER_BYTES);
    }
}
