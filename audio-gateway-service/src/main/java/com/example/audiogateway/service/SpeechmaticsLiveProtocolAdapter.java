package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.common.meeting.events.SpeakerAttribution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        return startMessage(sampleRateHz, List.of());
    }

    /**
     * @param additionalVocab user dictionary terms (proper nouns the acoustic
     *     model mis-hears — "Sevil Karakaş", "Sergen Bediroğlu"). Speechmatics
     *     accepts these only inside StartRecognition, which is why the caller
     *     resolves the client's context frame before building this message.
     *     Blank entries are skipped; an empty list omits the key entirely so
     *     the request stays byte-identical to the pre-dictionary shape.
     */
    String startMessage(final int sampleRateHz, final List<String> additionalVocab) {
        final ObjectNode root = objectMapper.createObjectNode();
        root.put("message", "StartRecognition");
        final ObjectNode format = root.putObject("audio_format");
        format.put("type", "raw");
        format.put("encoding", "pcm_s16le");
        format.put("sample_rate", sampleRateHz);
        final ObjectNode transcription = root.putObject("transcription_config");
        transcription.put("language", config.getLanguage());
        transcription.put("enable_partials", true);
        transcription.put("diarization", "speaker");
        transcription.put("max_delay", config.getMaxDelaySeconds());
        transcription.put("max_delay_mode", config.getMaxDelayMode());
        if (config.getPunctuationSensitivity() != null) {
            transcription.putObject("punctuation_overrides")
                    .put("sensitivity", config.getPunctuationSensitivity());
        }
        if (additionalVocab != null && !additionalVocab.isEmpty()) {
            final ArrayNode vocab = objectMapper.createArrayNode();
            for (final String term : additionalVocab) {
                if (term == null || term.isBlank()) {
                    continue;
                }
                vocab.addObject().put("content", term.trim());
            }
            if (!vocab.isEmpty()) {
                transcription.set("additional_vocab", vocab);
            }
        }
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
            case "AddPartialTranscript" -> partialEvent(event, acceptedSamples);
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

    private List<String> partialEvent(final JsonNode event, final long acceptedSamples) {
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
        putStageTimings(partial, acceptedSamples);
        return List.of(encode(partial));
    }

    /**
     * gitops#3419 RT-5 latency study: pins each event to (a) how much audio the
     * gateway has forwarded and (b) the gateway wall clock at emission, so the
     * client can split spoken->engine vs gateway->display lag. Optional fields —
     * the internal live-stt lane does not emit them and the proxy validator
     * accepts their absence.
     */
    private static void putStageTimings(final ObjectNode target, final long acceptedSamples) {
        target.put("audio_sent_ms", Math.round(acceptedSamples / 16.0d));
        target.put("emitted_at_ms", System.currentTimeMillis());
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
        final long sourceStart = attributionSourceStart(event.path("results"), lastFinalEndSample);
        result.put("source_start_sample", sourceStart);
        result.put("source_end_sample", sourceEnd);
        final ArrayNode turns = speakerTurns(event.path("results"), text, sourceStart, sourceEnd);
        if (turns != null) result.set("speakerTurns", turns);
        putStageTimings(result, acceptedSamples);
        lastFinalEndSample = sourceEnd;
        return List.of(encode(result));
    }

    private static long attributionSourceStart(JsonNode results, long fallback) {
        if (!results.isArray() || results.size() > 512) return fallback;
        long earliest = fallback;
        for (JsonNode item : results) {
            JsonNode start = item.path("start_time");
            if ("entity".equals(item.path("type").asText())) {
                earliest = Math.min(earliest, attributionSourceStart(item.path("written_form"), fallback));
            } else if (start.isNumber() && Double.isFinite(start.doubleValue()) && start.doubleValue() >= 0) {
                earliest = Math.min(earliest, Math.round(start.doubleValue() * 16000d));
            }
        }
        // Word spans can overlap a previous final (including punctuation-only
        // finals). The bounded PCM accumulator explicitly supports this overlap.
        return earliest;
    }

    /** Keep provider formatting authoritative. Ambiguous alignment stays unknown,
     * never a guessed attribution; word timing remains independent for overlap. */
    private ArrayNode speakerTurns(JsonNode results, String text, long startSample, long endSample) {
        if (!results.isArray() || results.isEmpty() || results.size() > 512) return null;
        ArrayNode words = objectMapper.createArrayNode();
        for (JsonNode item : results) {
            if ("entity".equals(item.path("type").asText())) {
                if (!item.path("written_form").isArray()) return null;
                item.path("written_form").forEach(words::add);
            } else {
                words.add(item);
            }
        }
        ArrayNode turns = objectMapper.createArrayNode();
        int offset = 0;
        for (JsonNode word : words) {
            String type = word.path("type").asText();
            if (!"word".equals(type) && !"punctuation".equals(type)) return null;
            JsonNode alternative = word.path("alternatives").path(0);
            String content = alternative.path("content").asText("");
            String speaker = alternative.path("speaker").asText("UU");
            if (content.isEmpty() || !speaker.matches("S[1-9][0-9]{0,2}|UU")) return null;
            int from = text.indexOf(content, offset);
            if (from < 0 || !text.substring(offset, from).isBlank()) return null;
            JsonNode start = word.path("start_time");
            JsonNode end = word.path("end_time");
            if (!start.isNumber() || !end.isNumber() || !Double.isFinite(start.doubleValue())
                    || !Double.isFinite(end.doubleValue())) return null;
            // Use the same sample clock as the window before quantizing to ms.
            // Rounding absolute ms while flooring duration can reject valid ends.
            long wordStartSample = Math.round(start.doubleValue() * 16000d);
            long wordEndSample = Math.round(end.doubleValue() * 16000d);
            if (wordStartSample < startSample || wordEndSample > endSample
                    || wordEndSample < wordStartSample) return null;
            long startMs = (wordStartSample - startSample) / 16;
            long endMs = (wordEndSample - startSample) / 16;
            turns.addObject().put("speaker", speaker).put("textStart", from)
                    .put("textEnd", from + content.length()).put("startMs", startMs).put("endMs", endMs);
            offset = from + content.length();
        }
        try {
            SpeakerAttribution.parseTurns(turns, text, (endSample - startSample) / 16);
            return turns;
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
