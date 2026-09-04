package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SpeechContextTermsTest {

    @Test
    void absentOrEmptyIsNull() {
        assertThat(SpeechContextTerms.normalize(null)).isNull();
        assertThat(SpeechContextTerms.normalize(List.of())).isNull();
        assertThat(SpeechContextTerms.normalize(List.of("   ", ""))).isNull();
        assertThat(SpeechContextTerms.toJson(null)).isNull();
        assertThat(SpeechContextTerms.fromJson(null)).isNull();
        assertThat(SpeechContextTerms.fromJson("[]")).isNull();
    }

    @Test
    void normalisesNfkcWhitespaceAndDuplicates() {
        // fullwidth "ＡＣＩＫ" folds to "ACIK"; inner whitespace collapses; exact duplicates drop
        List<String> out = SpeechContextTerms.normalize(Arrays.asList(
                "  Açık   Holding ", "\uFF21\uFF23\uFF29\uFF2B", "ACIK", "Açık Holding", "Sergen Bediroğlu"));
        assertThat(out).containsExactly("Açık Holding", "ACIK", "Sergen Bediroğlu");
        assertThat(SpeechContextTerms.fromJson(SpeechContextTerms.toJson(out))).isEqualTo(out);
    }

    @Test
    void rejectsControlCharactersAndUnsupportedPunctuation() {
        assertThatThrownBy(() -> SpeechContextTerms.normalize(List.of("bad\u0007term")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("SPEECH_CONTEXT_TERMS_CONTROL_CHARACTER");
                });
        assertThatThrownBy(() -> SpeechContextTerms.normalize(List.of("<script>")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getReason()).isEqualTo("SPEECH_CONTEXT_TERMS_UNSUPPORTED_CHARACTER"));
        assertThatThrownBy(() -> SpeechContextTerms.normalize(Collections.singletonList(null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getReason()).isEqualTo("SPEECH_CONTEXT_TERMS_NULL_TERM"));
    }

    @Test
    void enforcesTermCountAndLengthBudgets() {
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < SpeechContextTerms.MAX_TERMS + 1; i++) {
            tooMany.add("term" + i);
        }
        assertThatThrownBy(() -> SpeechContextTerms.normalize(tooMany))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getReason()).isEqualTo("SPEECH_CONTEXT_TERMS_TOO_MANY"));
        assertThatThrownBy(() -> SpeechContextTerms.normalize(List.of("x".repeat(SpeechContextTerms.MAX_TERM_LENGTH + 1))))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getReason()).isEqualTo("SPEECH_CONTEXT_TERMS_TERM_TOO_LONG"));
        List<String> longTotal = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            longTotal.add(("t" + i + " ").repeat(20).trim()); // 59-60 chars each ⇒ > 512 total
        }
        assertThatThrownBy(() -> SpeechContextTerms.normalize(longTotal))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getReason()).isEqualTo("SPEECH_CONTEXT_TERMS_TOTAL_TOO_LONG"));
    }
}
