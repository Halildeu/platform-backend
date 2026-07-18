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
        assertThat(frame.upstreamPayload()).isEqualTo("{\"type\":\"eof\"}");
    }

    @Test
    void rejectsUnknownControlExtraFieldsAndOversizedText() {
        assertInvalid("{\"type\":\"pause\"}", 64);
        assertInvalid("{\"type\":\"eof\",\"reason\":\"client\"}", 64);
        assertInvalid("not-json", 64);
        assertInvalid("{\"type\":\"eof\"}", 13);
    }

    private void assertInvalid(final String value, final int maxBytes) {
        assertThatThrownBy(() -> LiveStreamControlFrame.decode(value, maxBytes, objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("live stream terminal control is invalid");
    }
}
