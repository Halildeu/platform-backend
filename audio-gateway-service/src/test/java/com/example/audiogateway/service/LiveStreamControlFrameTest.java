package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LiveStreamControlFrameTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsOnlyOneFieldEofObjectAndCanonicalizesRelay() {
        final LiveStreamControlFrame frame = LiveStreamControlFrame.decode(
                " { \"type\" : \"eof\" } ", 64, objectMapper);

        assertThat(frame.type()).isEqualTo(LiveStreamControlFrame.Type.EOF);
        assertThat(frame.terminal()).isTrue();
        assertThat(frame.upstreamPayload(objectMapper)).isEqualTo("{\"type\":\"eof\"}");
    }

    @Test
    void normalizesDeduplicatesAndCanonicalizesContext() {
        final LiveStreamControlFrame frame = LiveStreamControlFrame.decode(
                "{\"type\":\"context\",\"terms\":[\"  Çağrı   Öztürk \","
                        + "\"çağrı öztürk\",\"Proje-24\"]}",
                4_096,
                objectMapper);

        assertThat(frame.type()).isEqualTo(LiveStreamControlFrame.Type.CONTEXT);
        assertThat(frame.terminal()).isFalse();
        assertThat(frame.terms()).containsExactly("Çağrı Öztürk", "Proje-24");
        assertThat(frame.upstreamPayload(objectMapper))
                .isEqualTo("{\"type\":\"context\",\"terms\":[\"Çağrı Öztürk\",\"Proje-24\"]}");
    }

    @Test
    void rejectsUnknownControlExtraFieldsAndOversizedText() {
        assertInvalid("{\"type\":\"pause\"}", 64);
        assertInvalid("{\"type\":\"eof\",\"reason\":\"client\"}", 64);
        assertInvalid("not-json", 64);
        assertInvalid("{\"type\":\"eof\"}", 13);
        assertInvalid("{\"type\":\"context\",\"terms\":[\"\"]}", 4_096);
        assertInvalid("{\"type\":\"context\",\"terms\":[\"unsafe/\"]}", 4_096);
        assertInvalid("{\"type\":\"context\",\"terms\":[\"line\\nfeed\"]}", 4_096);
        assertInvalid("{\"type\":\"context\",\"terms\":[] ,\"persist\":true}", 4_096);
    }

    private void assertInvalid(final String value, final int maxBytes) {
        assertThatThrownBy(() -> LiveStreamControlFrame.decode(value, maxBytes, objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("live stream control is invalid");
    }
}
