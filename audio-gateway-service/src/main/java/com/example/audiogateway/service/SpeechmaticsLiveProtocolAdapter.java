package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Translates one long-lived Speechmatics Realtime v2 stream to the gateway live contract. */
final class SpeechmaticsLiveProtocolAdapter {

    private static final String SOURCE = "speechmatics-realtime-v2";

    private final ObjectMapper objectMapper;
    private final AudioGatewayProperties.DirectStt.Speechmatics config;
    private final AtomicLong lastAcknowledgedAudioSequence = new AtomicLong();
    private final Sinks.Many<Long> acknowledgedAudioSequences =
            Sinks.many().replay().latest();
    private long nextFinalSequence;
    private long lastFinalEndSample;

    SpeechmaticsLiveProtocolAdapter(
            final ObjectMapper objectMapper,
            final AudioGatewayProperties.DirectStt.Speechmatics config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    URI endpoint() {
        final URI base = URI.create(config.getRealtimeUrl().trim());
        final String path = base.getPath() == null ? "" : base.getPath();
        if (path.endsWith("/" + config.getLanguage())) {
            return base;
        }
        return UriComponentsBuilder.fromUri(base)
                .path(path.endsWith("/") ? config.getLanguage() : "/" + config.getLanguage())
                .build(true)
                .toUri();
    }

    HttpHeaders authorizationHeaders() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(config.getApiKey());
        return headers;
    }

    String startMessage(final int sampleRateHz) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("message", "StartRecognition");
        final ObjectNode format = root.putObject("audio_format");
        format.put("type", "raw");
        format.put("encoding", "pcm_s16le");
        format.put("sample_rate", sampleRateHz);
        final ObjectNode transcription = root.putObject("transcription_config");
        transcription.put("language", config.getLanguage());
        transcription.put("enable_partials", true);
        transcription.put("max_delay", config.getMaxDelaySeconds());
        transcription.put("max_delay_mode", config.getMaxDelayMode());
        return encode(root);
    }

    private String endMessage(final long lastAcknowledgedSequence) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("message", "EndOfStream");
        root.put("last_seq_no", Math.max(0L, lastAcknowledgedSequence));
        return encode(root);
    }

    Mono<String> endMessageWhenAcknowledged(
            final long expectedAudioFrames,
            final Duration timeout) {
        // Speechmatics assigns the 1-based sequence and confirms each binary
        // AddAudio frame with AudioAdded. A local frame count alone is not receipt.
        if (expectedAudioFrames <= 0L) {
            return Mono.just(endMessage(0L));
        }
        return Mono.defer(() -> {
                    final long current = lastAcknowledgedAudioSequence.get();
                    if (current >= expectedAudioFrames) {
                        return Mono.just(endMessage(current));
                    }
                    return acknowledgedAudioSequences.asFlux()
                            .filter(sequence -> sequence >= expectedAudioFrames)
                            .next()
                            .map(this::endMessage);
                })
                .timeout(timeout, Mono.error(new SpeechmaticsAudioAcknowledgementException(
                        "Speechmatics did not acknowledge all audio before EndOfStream")));
    }

    long lastAcknowledgedAudioSequence() {
        return lastAcknowledgedAudioSequence.get();
    }

    List<String> translate(final String value, final long acceptedSamples) {
        final JsonNode event;
        try {
            event = objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Speechmatics live event is invalid JSON", error);
        }
        final String message = event.path("message").asText("");
        return switch (message) {
            case "RecognitionStarted" -> List.of(readyEvent());
            case "AddPartialTranscript" -> partialEvent(event);
            case "AddTranscript" -> finalEvent(event, acceptedSamples);
            case "EndOfTranscript" -> List.of("{\"type\":\"eof_ack\"}", "{\"type\":\"drained\"}");
            case "Error" -> {
                acknowledgedAudioSequences.tryEmitError(
                        new SpeechmaticsAudioAcknowledgementException(
                                "Speechmatics failed before acknowledging all audio"));
                yield List.of("{\"type\":\"error\",\"msg\":\"speechmatics stream failed\"}");
            }
            case "AudioAdded" -> {
                observeAudioAdded(event);
                yield List.of();
            }
            case "Info", "Warning" -> List.of();
            default -> List.of();
        };
    }

    private void observeAudioAdded(final JsonNode event) {
        final JsonNode value = event.path("seq_no");
        if (!value.isIntegralNumber()) {
            throw new SpeechmaticsAudioAcknowledgementException(
                    "Speechmatics AudioAdded sequence is invalid");
        }
        final long sequence = value.asLong();
        while (true) {
            final long previous = lastAcknowledgedAudioSequence.get();
            if (sequence == previous) {
                return;
            }
            if (sequence != previous + 1L) {
                throw new SpeechmaticsAudioAcknowledgementException(
                        "Speechmatics AudioAdded sequence is not contiguous");
            }
            if (lastAcknowledgedAudioSequence.compareAndSet(previous, sequence)) {
                acknowledgedAudioSequences.tryEmitNext(sequence);
                return;
            }
        }
    }

    private String readyEvent() {
        final ObjectNode ready = objectMapper.createObjectNode();
        ready.put("type", "ready");
        ready.put("sample_rate", 16_000);
        ready.put("live_model", SOURCE);
        ready.put("final_model", SOURCE);
        ready.put("partial_mode", "stable-v1");
        ready.put("protocol", LiveSttWebSocketProxyHandler.UPSTREAM_PROTOCOL);
        ready.putArray("capabilities")
                .add("eof")
                .add(LiveSttWebSocketProxyHandler.UPSTREAM_PROTOCOL);
        ready.put("supports_eof", true);
        ready.put("terminal_timeout_ms", 30_000);
        return encode(ready);
    }

    private List<String> partialEvent(final JsonNode event) {
        final String text = event.path("metadata").path("transcript").asText("").trim();
        if (text.isEmpty()) {
            return List.of();
        }
        final ObjectNode partial = objectMapper.createObjectNode();
        partial.put("type", "partial");
        partial.put("seq", nextFinalSequence);
        partial.put("confirmed", "");
        partial.put("tentative", text);
        partial.put("elapsed_ms", elapsedMs(event));
        partial.put("rms", 0.0d);
        partial.put("source", SOURCE);
        return List.of(encode(partial));
    }

    private List<String> finalEvent(final JsonNode event, final long acceptedSamples) {
        final String text = event.path("metadata").path("transcript").asText("").trim();
        if (text.isEmpty() || acceptedSamples <= lastFinalEndSample) {
            return List.of();
        }
        final long providerEnd = Math.round(
                event.path("metadata").path("end_time").asDouble(-1.0d) * 16_000.0d);
        final long sourceEnd = providerEnd > lastFinalEndSample
                ? Math.min(providerEnd, acceptedSamples)
                : acceptedSamples;
        if (sourceEnd <= lastFinalEndSample) {
            return List.of();
        }
        final ObjectNode result = objectMapper.createObjectNode();
        result.put("type", "final");
        result.put("seq", nextFinalSequence++);
        result.put("text", text);
        result.put("reason", "speechmatics_final");
        result.put("elapsed_ms", elapsedMs(event));
        result.put("rms", 0.0d);
        result.put("source_start_sample", lastFinalEndSample);
        result.put("source_end_sample", sourceEnd);
        lastFinalEndSample = sourceEnd;
        return List.of(encode(result));
    }

    private static long elapsedMs(final JsonNode event) {
        final double seconds = event.path("metadata").path("end_time").asDouble(0.0d);
        return Math.max(0L, Math.round(seconds * 1_000.0d));
    }

    private String encode(final JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Speechmatics live protocol encoding failed", error);
        }
    }

    static final class SpeechmaticsAudioAcknowledgementException extends RuntimeException {
        SpeechmaticsAudioAcknowledgementException(final String message) {
            super(message);
        }
    }
}
