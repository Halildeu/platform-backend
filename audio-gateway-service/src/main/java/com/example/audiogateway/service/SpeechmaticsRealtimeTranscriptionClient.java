package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.TranscriptResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Speechmatics Realtime v2 adapter for one bounded PCM16 aggregation window. */
public final class SpeechmaticsRealtimeTranscriptionClient
        implements DirectSttTranscriptionClient {

    private static final String START_RECOGNITION = "StartRecognition";
    private static final String RECOGNITION_STARTED = "RecognitionStarted";
    private static final String ADD_TRANSCRIPT = "AddTranscript";
    private static final String END_OF_TRANSCRIPT = "EndOfTranscript";
    private static final String ERROR = "Error";

    private final WebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final AudioGatewayProperties.DirectStt.Speechmatics config;
    private final URI endpoint;

    public SpeechmaticsRealtimeTranscriptionClient(
            final WebSocketClient webSocketClient,
            final ObjectMapper objectMapper,
            final AudioGatewayProperties.DirectStt.Speechmatics config) {
        this.webSocketClient = webSocketClient;
        this.objectMapper = objectMapper;
        this.config = config;
        this.endpoint = languageEndpoint(config.getRealtimeUrl(), config.getLanguage());
    }

    @Override
    public Mono<TranscriptResult> transcribe(final DirectSttTranscriptionRequest request) {
        if (request.audioFormat() != AudioFormat.PCM16
                || request.sampleRateHz() <= 0
                || request.channels() != 1
                || request.audio().length == 0
                || (request.audio().length & 1) != 0) {
            return Mono.error(new SpeechmaticsProtocolException(
                    "Speechmatics adapter requires non-empty mono PCM16 audio with complete samples"));
        }

        final long startedAtNanos = System.nanoTime();
        final HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(config.getApiKey().trim());
        final Sinks.One<Void> recognitionStarted = Sinks.one();
        final Sinks.One<TranscriptResult> completed = Sinks.one();
        final StringBuilder transcript = new StringBuilder();
        final ArrayNode segments = objectMapper.createArrayNode();
        final AtomicBoolean terminalObserved = new AtomicBoolean();
        final int frameCount = (request.audio().length + config.getAudioChunkBytes() - 1)
                / config.getAudioChunkBytes();

        return webSocketClient.execute(endpoint, headers, session -> {
                    final Flux<WebSocketMessage> outbound = Flux.concat(
                            Mono.fromCallable(() -> session.textMessage(startMessage(request))),
                            recognitionStarted.asMono().thenMany(Flux.concat(
                                    audioFrames(session, request.audio()),
                                    Mono.fromCallable(() -> session.textMessage(
                                            endMessage(frameCount))))));

                    final Mono<Void> send = session.send(outbound);
                    final Mono<Void> receive = session.receive()
                            .handle((message, sink) -> {
                                if (message.getType() != WebSocketMessage.Type.TEXT) {
                                    sink.error(new SpeechmaticsProtocolException(
                                            "Speechmatics returned a non-text control frame"));
                                    return;
                                }
                                final JsonNode event;
                                try {
                                    event = objectMapper.readTree(message.getPayloadAsText());
                                } catch (final JsonProcessingException ex) {
                                    sink.error(new SpeechmaticsProtocolException(
                                            "Speechmatics returned invalid JSON", ex));
                                    return;
                                }
                                final String type = event.path("message").asText("");
                                switch (type) {
                                    case RECOGNITION_STARTED -> recognitionStarted.tryEmitEmpty();
                                    case ADD_TRANSCRIPT -> appendFinal(event, transcript, segments);
                                    case END_OF_TRANSCRIPT -> {
                                        terminalObserved.set(true);
                                        completed.tryEmitValue(toResult(
                                                transcript,
                                                segments,
                                                request,
                                                startedAtNanos));
                                        sink.complete();
                                    }
                                    case ERROR -> sink.error(new SpeechmaticsProtocolException(
                                            "Speechmatics returned an error event"));
                                    default -> {
                                        // Partials, AudioAdded, Info and Warning are non-terminal.
                                    }
                                }
                            })
                            .doOnComplete(() -> {
                                if (!terminalObserved.get()) {
                                    completed.tryEmitError(new SpeechmaticsProtocolException(
                                            "Speechmatics connection ended before EndOfTranscript"));
                                }
                            })
                            .then();
                    return Mono.when(send, receive);
                })
                .then(completed.asMono());
    }

    @Override
    public String providerId() {
        return "speechmatics";
    }

    private String startMessage(final DirectSttTranscriptionRequest request) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("message", START_RECOGNITION);
        final ObjectNode audioFormat = root.putObject("audio_format");
        audioFormat.put("type", "raw");
        audioFormat.put("encoding", "pcm_s16le");
        audioFormat.put("sample_rate", request.sampleRateHz());
        final ObjectNode transcription = root.putObject("transcription_config");
        transcription.put("language", config.getLanguage());
        transcription.put("enable_partials", false);
        transcription.put("max_delay", config.getMaxDelaySeconds());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (final JsonProcessingException ex) {
            throw new SpeechmaticsProtocolException(
                    "Could not encode Speechmatics StartRecognition", ex);
        }
    }

    private Flux<WebSocketMessage> audioFrames(
            final WebSocketSession session,
            final byte[] audio) {
        final int chunkBytes = config.getAudioChunkBytes();
        final int frameCount = (audio.length + chunkBytes - 1) / chunkBytes;
        return Flux.range(0, frameCount)
                .map(index -> {
                    final int offset = index * chunkBytes;
                    final int length = Math.min(chunkBytes, Math.max(0, audio.length - offset));
                    final ByteBuffer view = ByteBuffer.wrap(audio, offset, length).slice();
                    return session.binaryMessage(factory -> factory.wrap(view));
                });
    }

    private String endMessage(final int frameCount) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("message", "EndOfStream");
        root.put("last_seq_no", frameCount);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (final JsonProcessingException ex) {
            throw new SpeechmaticsProtocolException("Could not encode Speechmatics EndOfStream", ex);
        }
    }

    private static void appendFinal(
            final JsonNode event,
            final StringBuilder transcript,
            final ArrayNode segments) {
        final String text = event.path("metadata").path("transcript").asText("").trim();
        if (!text.isEmpty()) {
            if (!transcript.isEmpty()) {
                transcript.append(' ');
            }
            transcript.append(text);
        }
        final JsonNode results = event.path("results");
        if (results.isArray()) {
            results.forEach(segments::add);
        }
    }

    private TranscriptResult toResult(
            final StringBuilder transcript,
            final ArrayNode segments,
            final DirectSttTranscriptionRequest request,
            final long startedAtNanos) {
        final double elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000.0d;
        return new TranscriptResult(
                transcript.toString(),
                config.getLanguage(),
                null,
                request.audioDurationMs() / 1_000.0d,
                elapsedMs,
                "speechmatics-realtime-v2",
                null,
                "speechmatics-saas",
                segments);
    }

    private static URI languageEndpoint(final String baseUrl, final String language) {
        final URI base = URI.create(baseUrl.trim());
        final String path = base.getPath() == null ? "" : base.getPath();
        if (path.endsWith("/" + language)) {
            return base;
        }
        return UriComponentsBuilder.fromUri(base)
                .path(path.endsWith("/") ? language : "/" + language)
                .build(true)
                .toUri();
    }

    static final class SpeechmaticsProtocolException extends RuntimeException {
        SpeechmaticsProtocolException(final String message) {
            super(message);
        }

        SpeechmaticsProtocolException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
