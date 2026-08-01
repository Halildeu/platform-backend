package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.TranscriptResult;
import java.time.Duration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/** Existing internal live-stt HTTP contract, isolated behind the provider-neutral port. */
public final class InternalDirectSttTranscriptionClient implements DirectSttTranscriptionClient {

    private static final String AUDIO_PART = "audio";

    private final WebClient webClient;
    private final String transcribeUri;
    private final Duration responseTimeout;

    public InternalDirectSttTranscriptionClient(
            final WebClient webClient,
            final AudioGatewayProperties.DirectStt config) {
        this.webClient = webClient;
        this.transcribeUri = config.getTranscribeUrl().trim();
        this.responseTimeout = Duration.ofMillis(config.getResponseTimeoutMs());
    }

    @Override
    public Mono<TranscriptResult> transcribe(final DirectSttTranscriptionRequest request) {
        final MultipartBodyBuilder body = new MultipartBodyBuilder();
        final byte[] partBytes;
        final AudioFormat partFormat;
        if (request.audioFormat() == AudioFormat.PCM16) {
            partBytes = WavEncoder.pcm16ToWav(
                    request.audio(), request.sampleRateHz(), request.channels());
            partFormat = AudioFormat.WAV;
        } else {
            partBytes = request.audio();
            partFormat = request.audioFormat();
        }
        body.part(AUDIO_PART, new NamedByteArrayResource(partBytes, audioFilename(partFormat)))
                .contentType(MediaType.parseMediaType(partFormat.mediaType()));

        final String uri = UriComponentsBuilder.fromUriString(transcribeUri)
                .queryParam("meeting_id", nullSafe(request.meetingId()))
                .queryParam("session_id", nullSafe(request.sessionId()))
                .queryParam("device_id", nullSafe(request.deviceId()))
                .queryParam("language", nullSafe(request.language()))
                .build()
                .toUriString();

        return webClient.post()
                .uri(uri)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .bodyToMono(TranscriptResult.class)
                .timeout(responseTimeout);
    }

    @Override
    public String providerId() {
        return "internal";
    }

    @Override
    public String computePlaneId() {
        return "live-stt";
    }

    private static String audioFilename(final AudioFormat format) {
        return switch (format) {
            case WAV -> "chunk.wav";
            case WEBM_OPUS -> "chunk.webm";
            case PCM16 -> "chunk.pcm";
            case MP3 -> "chunk.mp3";
            case M4A -> "chunk.m4a";
            case OGG -> "chunk.ogg";
            case FLAC -> "chunk.flac";
        };
    }

    private static String nullSafe(final String value) {
        return value == null ? "" : value;
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(final byte[] bytes, final String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
