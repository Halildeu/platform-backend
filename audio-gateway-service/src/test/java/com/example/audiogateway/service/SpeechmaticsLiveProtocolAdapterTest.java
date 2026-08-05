package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
        // Vendor default: flexible lets the engine keep a formatted entity intact
        // instead of hard-cutting at the cap (fixed was our old hardcode).
        assertThat(start.path("transcription_config").path("max_delay_mode").asText())
                .isEqualTo("flexible");
    }

    @Test
    void honoursAConfiguredFixedMaxDelayMode() throws Exception {
        final AudioGatewayProperties.DirectStt.Speechmatics config = config();
        config.setMaxDelayMode("fixed");
        final SpeechmaticsLiveProtocolAdapter adapter =
                new SpeechmaticsLiveProtocolAdapter(objectMapper, config);

        final JsonNode start = objectMapper.readTree(adapter.startMessage(16_000));

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
        // RT-5 latency-study stage timings: 12_800 samples @16kHz -> 800 ms of
        // forwarded audio at the partial, 16_000 -> 1000 ms at the final.
        assertThat(partial.path("audio_sent_ms").asLong()).isEqualTo(800L);
        assertThat(partial.path("emitted_at_ms").asLong()).isPositive();
        assertThat(finalEvent.path("audio_sent_ms").asLong()).isEqualTo(1_000L);
        assertThat(finalEvent.path("emitted_at_ms").asLong()).isPositive();
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
    void waitsForEveryProviderAudioAcknowledgementBeforeEnding() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();
        final AtomicReference<String> terminal = new AtomicReference<>();

        adapter.endMessageWhenAcknowledged(2L, Duration.ofSeconds(1))
                .subscribe(terminal::set);

        adapter.translate("{\"message\":\"AudioAdded\",\"seq_no\":1}", 0L);
        assertThat(terminal).hasValue(null);
        adapter.translate("{\"message\":\"AudioAdded\",\"seq_no\":2}", 0L);

        assertThat(objectMapper.readTree(terminal.get()).path("last_seq_no").asLong())
                .isEqualTo(2L);
    }

    @Test
    void replaysAnAlreadyObservedAcknowledgementToTheEofWaiter() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();

        adapter.translate("{\"message\":\"AudioAdded\",\"seq_no\":1}", 0L);
        final String terminal = adapter.endMessageWhenAcknowledged(
                        1L, Duration.ofSeconds(1))
                .block();

        assertThat(objectMapper.readTree(terminal).path("last_seq_no").asLong())
                .isEqualTo(1L);
    }

    @Test
    void failsClosedWhenAnAudioAcknowledgementIsMissingOrOutOfOrder() {
        final SpeechmaticsLiveProtocolAdapter missing = adapter();

        assertThatThrownBy(() -> missing.endMessageWhenAcknowledged(
                                1L, Duration.ofMillis(20))
                        .block())
                .isInstanceOf(
                        SpeechmaticsLiveProtocolAdapter
                                .SpeechmaticsAudioAcknowledgementException.class)
                .hasMessageContaining("did not acknowledge all audio");

        final SpeechmaticsLiveProtocolAdapter gap = adapter();
        assertThatThrownBy(() -> gap.translate(
                        "{\"message\":\"AudioAdded\",\"seq_no\":2}", 0L))
                .isInstanceOf(
                        SpeechmaticsLiveProtocolAdapter
                                .SpeechmaticsAudioAcknowledgementException.class)
                .hasMessageContaining("not contiguous");
    }

    @Test
    void zeroAudioEndsWithTheProtocolSentinel() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();

        assertThat(objectMapper.readTree(adapter.endMessageWhenAcknowledged(
                                0L, Duration.ofSeconds(1))
                        .block()).path("last_seq_no").asLong())
                .isZero();
    }

    private SpeechmaticsLiveProtocolAdapter adapter() {
        return new SpeechmaticsLiveProtocolAdapter(objectMapper, config());
    }

    private AudioGatewayProperties.DirectStt.Speechmatics config() {
        final AudioGatewayProperties.DirectStt.Speechmatics config =
                new AudioGatewayProperties.DirectStt.Speechmatics();
        config.setRealtimeUrl("wss://eu2.rt.speechmatics.com/v2");
        config.setApiKey("test-key-not-a-secret");
        config.setLanguage("tr");
        config.setMaxDelaySeconds(1.0d);
        return config;
    }

    // ── Faz 24 gitops#3435 dilim-3: kullanıcı sözlüğü Speechmatics'e de gider ──

    @Test
    void carriesTheUserDictionaryAsAdditionalVocab() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();

        final JsonNode start = objectMapper.readTree(
                adapter.startMessage(16_000, List.of("Sevil Karakaş", "Sergen Bediroğlu")));

        final JsonNode vocab = start.path("transcription_config").path("additional_vocab");
        assertThat(vocab.isArray()).isTrue();
        assertThat(vocab).hasSize(2);
        assertThat(vocab.get(0).path("content").asText()).isEqualTo("Sevil Karakaş");
        assertThat(vocab.get(1).path("content").asText()).isEqualTo("Sergen Bediroğlu");
    }

    @Test
    void omitsAdditionalVocabEntirelyWhenTheDictionaryIsEmpty() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();

        // Both the no-dictionary overload and an explicitly empty list must keep
        // the request byte-identical to the pre-dictionary shape — an empty
        // `additional_vocab: []` is a different request and we do not send one.
        for (final String message :
                List.of(adapter.startMessage(16_000), adapter.startMessage(16_000, List.of()))) {
            final JsonNode start = objectMapper.readTree(message);
            assertThat(start.path("transcription_config").has("additional_vocab")).isFalse();
        }
    }

    @Test
    void skipsBlankDictionaryEntriesAndTrimsTheRest() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();

        final JsonNode start = objectMapper.readTree(
                adapter.startMessage(16_000, java.util.Arrays.asList(
                        "  Ayşe Yıldız  ", "", "   ", null, "Mahmut Ateş")));

        final JsonNode vocab = start.path("transcription_config").path("additional_vocab");
        assertThat(vocab).hasSize(2);
        assertThat(vocab.get(0).path("content").asText()).isEqualTo("Ayşe Yıldız");
        assertThat(vocab.get(1).path("content").asText()).isEqualTo("Mahmut Ateş");
    }

    @Test
    void aDictionaryOfOnlyBlanksLeavesTheRequestUnchanged() throws Exception {
        final SpeechmaticsLiveProtocolAdapter adapter = adapter();

        final JsonNode start =
                objectMapper.readTree(adapter.startMessage(16_000, List.of(" ", "\t")));

        assertThat(start.path("transcription_config").has("additional_vocab")).isFalse();
    }

}
