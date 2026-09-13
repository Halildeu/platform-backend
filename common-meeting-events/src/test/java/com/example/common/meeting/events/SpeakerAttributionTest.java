package com.example.common.meeting.events;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpeakerAttributionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String meeting = "22222222-2222-4222-8222-222222222222";

    @Test
    void scopesAnonymousLabelsToTenantMeetingSessionAndTransport() {
        UUID base = SpeakerAttribution.scope("42", meeting, "SES-a", 1);
        assertThat(base).isEqualTo(SpeakerAttribution.scope("42", meeting, "SES-a", 1));
        assertThat(base).isNotEqualTo(SpeakerAttribution.scope("43", meeting, "SES-a", 1));
        assertThat(base).isNotEqualTo(SpeakerAttribution.scope("42", UUID.randomUUID().toString(), "SES-a", 1));
        assertThat(base).isNotEqualTo(SpeakerAttribution.scope("42", meeting, "SES-b", 1));
        assertThat(base).isNotEqualTo(SpeakerAttribution.scope("42", meeting, "SES-a", 2));
    }

    @Test
    void roundTripsOverlapWithoutStoringTextOrIdentity() {
        var a = attribution();
        assertThat(SpeakerAttribution.parse(a.encode(), a.scope(), "hello world", 1000)).isEqualTo(a);
        assertThat(a.encode()).doesNotContain("hello", "world", "voiceprint");
        assertThat(a.speakerId("UU")).isNull();
        assertThat(a.speakerId("S1")).isNotEqualTo(a.speakerId("S2"));
    }

    @Test
    void rejectsCrossSessionPayloadAndOutOfWindowTime() {
        var a = attribution();
        assertThatThrownBy(() -> SpeakerAttribution.parse(a.encode(), UUID.randomUUID(), "hello world", 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("hello");
        assertThatThrownBy(() -> SpeakerAttribution.parse(a.encode(), a.scope(), "hello world", 700))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnattributedTextAndSurrogateSplits() throws Exception {
        var node = json.valueToTree(attribution().turns());
        assertThatThrownBy(() -> SpeakerAttribution.parseTurns(node, "hello world extra", 1000))
                .isInstanceOf(IllegalArgumentException.class);
        var split = json.readTree("""
                [{"speaker":"S1","textStart":0,"textEnd":1,"startMs":0,"endMs":1},
                 {"speaker":"S2","textStart":1,"textEnd":2,"startMs":1,"endMs":2}]
                """);
        assertThatThrownBy(() -> SpeakerAttribution.parseTurns(split, "\uD83D\uDE00", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNamedIdentityAndUnknownFields() throws Exception {
        assertThatThrownBy(() -> new SpeakerAttribution.Turn("Alice", 0, 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("Alice");
        var node = json.readTree("""
                [{"speaker":"S1","textStart":0,"textEnd":1,"startMs":0,"endMs":1,"voiceprint":"x"}]
                """);
        assertThatThrownBy(() -> SpeakerAttribution.parseTurns(node, "a", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SpeakerAttribution attribution() {
        return new SpeakerAttribution(SpeakerAttribution.scope("42", meeting, "SES-a", 1), List.of(
                new SpeakerAttribution.Turn("S1", 0, 5, 0, 800),
                new SpeakerAttribution.Turn("S2", 6, 11, 600, 1000)));
    }
}
