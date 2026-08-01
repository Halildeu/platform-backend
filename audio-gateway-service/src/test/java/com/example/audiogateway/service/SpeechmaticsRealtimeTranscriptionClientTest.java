package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.TranscriptResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class SpeechmaticsRealtimeTranscriptionClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private DisposableServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow(Duration.ofSeconds(5));
        }
    }

    @Test
    void sendsProtocolInOrderAndNormalizesFinalTranscript() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        final List<String> controls = new CopyOnWriteArrayList<>();
        final AtomicInteger audioBytes = new AtomicInteger();
        final AtomicReference<String> authorization = new AtomicReference<>();

        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get("/v2/tr", (request, response) -> {
                    authorization.set(request.requestHeaders().get("Authorization"));
                    return response.sendWebsocket((in, out) -> {
                        final var events = in.receiveFrames()
                                .<TextWebSocketFrame>handle((frame, sink) -> {
                                    if (frame instanceof BinaryWebSocketFrame binary) {
                                        audioBytes.set(binary.content().readableBytes());
                                        sink.next(new TextWebSocketFrame(
                                                "{\"message\":\"AddTranscript\","
                                                        + "\"metadata\":{\"transcript\":\"Gundem onaylandi.\"},"
                                                        + "\"results\":[{\"type\":\"word\","
                                                        + "\"alternatives\":[{\"content\":\"Gundem\","
                                                        + "\"confidence\":0.99}]}]}"));
                                        return;
                                    }
                                    if (frame instanceof TextWebSocketFrame text) {
                                        controls.add(text.text());
                                        final String type;
                                        try {
                                            type = mapper.readTree(text.text())
                                                    .path("message")
                                                    .asText();
                                        } catch (final Exception ex) {
                                            sink.error(ex);
                                            return;
                                        }
                                        if ("StartRecognition".equals(type)) {
                                            sink.next(new TextWebSocketFrame(
                                                    "{\"message\":\"RecognitionStarted\","
                                                            + "\"id\":\"fixture-session\"}"));
                                        } else if ("EndOfStream".equals(type)) {
                                            sink.next(new TextWebSocketFrame(
                                                    "{\"message\":\"EndOfTranscript\"}"));
                                        }
                                    }
                                });
                        return out.sendObject(events);
                    });
                }))
                .bindNow();

        final AudioGatewayProperties.DirectStt.Speechmatics config =
                new AudioGatewayProperties.DirectStt.Speechmatics();
        config.setRealtimeUrl("ws://127.0.0.1:" + server.port() + "/v2");
        config.setAllowInsecure(true);
        config.setApiKey("test-key-not-a-secret");
        config.setLanguage("tr");

        final SpeechmaticsRealtimeTranscriptionClient client =
                new SpeechmaticsRealtimeTranscriptionClient(
                        new ReactorNettyWebSocketClient(), mapper, config);
        final byte[] audio = new byte[32_000];
        final TranscriptResult result = client.transcribe(new DirectSttTranscriptionRequest(
                        audio,
                        AudioFormat.PCM16,
                        16_000,
                        1,
                        "meeting-fixture",
                        "session-fixture",
                        "device-fixture",
                        "tr",
                        1_000))
                .block(TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.text()).isEqualTo("Gundem onaylandi.");
        assertThat(result.language()).isEqualTo("tr");
        assertThat(result.model()).isEqualTo("speechmatics-realtime-v2");
        assertThat(result.durationSeconds()).isEqualTo(1.0d);
        assertThat(result.segments()).hasSize(1);
        assertThat(audioBytes).hasValue(audio.length);
        assertThat(authorization).hasValue("Bearer test-key-not-a-secret");
        assertThat(controls).hasSize(2);

        final JsonNode start = mapper.readTree(controls.getFirst());
        assertThat(start.path("message").asText()).isEqualTo("StartRecognition");
        assertThat(start.path("audio_format").path("encoding").asText())
                .isEqualTo("pcm_s16le");
        assertThat(start.path("audio_format").path("sample_rate").asInt()).isEqualTo(16_000);
        assertThat(start.path("transcription_config").path("language").asText())
                .isEqualTo("tr");
        assertThat(mapper.readTree(controls.get(1)).path("last_seq_no").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsNonMonoPcmWithoutOpeningConnection() {
        final AudioGatewayProperties.DirectStt.Speechmatics config =
                new AudioGatewayProperties.DirectStt.Speechmatics();
        config.setApiKey("test-key-not-a-secret");
        final SpeechmaticsRealtimeTranscriptionClient client =
                new SpeechmaticsRealtimeTranscriptionClient(
                        new ReactorNettyWebSocketClient(), new ObjectMapper(), config);

        assertThatThrownBy(() -> client.transcribe(new DirectSttTranscriptionRequest(
                                new byte[16],
                                AudioFormat.WAV,
                                16_000,
                                1,
                                "meeting",
                                "session",
                                "device",
                                "tr",
                                1))
                        .block(TIMEOUT))
                .isInstanceOf(
                        SpeechmaticsRealtimeTranscriptionClient.SpeechmaticsProtocolException.class)
                .hasMessageContaining("mono PCM16");
    }

    @Test
    void rejectsIncompletePcmSampleWithoutOpeningConnection() {
        final AudioGatewayProperties.DirectStt.Speechmatics config =
                new AudioGatewayProperties.DirectStt.Speechmatics();
        config.setApiKey("test-key-not-a-secret");
        final SpeechmaticsRealtimeTranscriptionClient client =
                new SpeechmaticsRealtimeTranscriptionClient(
                        new ReactorNettyWebSocketClient(), new ObjectMapper(), config);

        assertThatThrownBy(() -> client.transcribe(new DirectSttTranscriptionRequest(
                                new byte[3],
                                AudioFormat.PCM16,
                                16_000,
                                1,
                                "meeting",
                                "session",
                                "device",
                                "tr",
                                1))
                        .block(TIMEOUT))
                .isInstanceOf(
                        SpeechmaticsRealtimeTranscriptionClient.SpeechmaticsProtocolException.class)
                .hasMessageContaining("complete samples");
    }

    @Test
    void providerErrorFailsClosedWithoutExposingProviderReason() {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/v2/tr", (in, out) -> out.sendObject(
                        in.receiveFrames().<TextWebSocketFrame>handle((frame, sink) -> {
                            if (frame instanceof TextWebSocketFrame text
                                    && text.text().contains("StartRecognition")) {
                                sink.next(new TextWebSocketFrame(
                                        "{\"message\":\"RecognitionStarted\"}"));
                            } else if (frame instanceof BinaryWebSocketFrame) {
                                sink.next(new TextWebSocketFrame(
                                        "{\"message\":\"Error\","
                                                + "\"reason\":\"provider-private-detail\"}"));
                            }
                        }))))
                .bindNow();

        final AudioGatewayProperties.DirectStt.Speechmatics config =
                new AudioGatewayProperties.DirectStt.Speechmatics();
        config.setRealtimeUrl("ws://127.0.0.1:" + server.port() + "/v2");
        config.setAllowInsecure(true);
        config.setApiKey("test-key-not-a-secret");
        final SpeechmaticsRealtimeTranscriptionClient client =
                new SpeechmaticsRealtimeTranscriptionClient(
                        new ReactorNettyWebSocketClient(), new ObjectMapper(), config);

        assertThatThrownBy(() -> client.transcribe(new DirectSttTranscriptionRequest(
                                new byte[3_200],
                                AudioFormat.PCM16,
                                16_000,
                                1,
                                "meeting",
                                "session",
                                "device",
                                "tr",
                                100))
                        .block(TIMEOUT))
                .isInstanceOf(
                        SpeechmaticsRealtimeTranscriptionClient.SpeechmaticsProtocolException.class)
                .hasMessage("Speechmatics returned an error event")
                .hasMessageNotContaining("provider-private-detail");
    }
}
