package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpeechmaticsLiveProtocolAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enablesPartialsAndUsesTheConfiguredFinalizationPolicy() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();

        final JsonNode start = objectMapper.readTree(adapter.startMessage(16_000));

        assertThat(start.path("message").asText()).isEqualTo("StartRecognition");
        assertThat(start.path("audio_format").path("encoding").asText())
                .isEqualTo("pcm_s16le");
        assertThat(start.path("transcription_config").path("enable_partials").asBoolean())
                .isTrue();
        assertThat(start.path("transcription_config").path("max_delay").asDouble())
                .isEqualTo(1.0d);
        assertThat(start.path("transcription_config").path("max_delay_mode").asText())
                .isEqualTo("fixed");
    }

    @Test
    void mapsIncrementalAndFinalEventsToOneStableSequence() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();

        final JsonNode ready = objectMapper.readTree(adapter.translate(
                "{\"message\":\"RecognitionStarted\"}", 0L).getFirst());
        final JsonNode partial = objectMapper.readTree(adapter.translate(
                "{\"message\":\"AddPartialTranscript\","
                        + "\"metadata\":{\"transcript\":\"gundem gorusuluyor\","
                        + "\"end_time\":0.8}}",
                12_800L).getFirst());
        final JsonNode finalEvent = objectMapper.readTree(adapter.translate(
                "{\"message\":\"AddTranscript\","
                        + "\"metadata\":{\"transcript\":\"Gundem gorusuluyor.\","
                        + "\"end_time\":1.0}}",
                16_000L).getFirst());

        assertThat(ready.path("type").asText()).isEqualTo("ready");
        assertThat(ready.path("partial_mode").asText()).isEqualTo("stable-v1");
        assertThat(partial.path("type").asText()).isEqualTo("partial");
        assertThat(partial.path("seq").asLong()).isZero();
        assertThat(partial.path("tentative").asText()).isEqualTo("gundem gorusuluyor");
        assertThat(finalEvent.path("type").asText()).isEqualTo("final");
        assertThat(finalEvent.path("seq").asLong()).isZero();
        assertThat(finalEvent.path("source_start_sample").asLong()).isZero();
        assertThat(finalEvent.path("source_end_sample").asLong()).isEqualTo(16_000L);
    }

    @Test
    void mapsProviderTerminalToGatewayEofThenDrain() {
        final List<String> terminal = adapter().translate(
                "{\"message\":\"EndOfTranscript\"}", 16_000L);

        assertThat(terminal).containsExactly(
                "{\"type\":\"eof_ack\"}",
                "{\"type\":\"drained\"}");
    }

    @Test
    void endsWithTheZeroBasedSequenceOfTheLastAudioFrame() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();

        assertThat(objectMapper.readTree(adapter.endMessage(1L)).path("last_seq_no").asLong())
                .isZero();
        assertThat(objectMapper.readTree(adapter.endMessage(131L)).path("last_seq_no").asLong())
                .isEqualTo(130L);
    }

    private SpeechmaticsLiveProtocolAdapter adapter() {
        final AudioGatewayProperties.DirectStt.Speechmatics config =
                new AudioGatewayProperties.DirectStt.Speechmatics();
        config.setRealtimeUrl("wss://eu2.rt.speechmatics.com/v2");
        config.setApiKey("test-key-not-a-secret");
        config.setLanguage("tr");
        config.setMaxDelaySeconds(1.0d);
        return new SpeechmaticsLiveProtocolAdapter(objectMapper, config);
    }
}
