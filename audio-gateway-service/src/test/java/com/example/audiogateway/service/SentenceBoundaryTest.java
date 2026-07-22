package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Turkish sentence-boundary detection (Faz 24 — transcript readability).
 *
 * <p>The asymmetry these tests encode: splitting a sentence that had not ended is the
 * defect being fixed, so ambiguous trailing dots must resolve to "not a boundary".
 * Merging one fragment too many is recoverable — {@link SentenceAssembler}'s bounds
 * still close the line.
 */
class SentenceBoundaryTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "İzlediğiniz için teşekkür ederim.",
                "Bugün toplantıya katılacak mısınız?",
                "Bu kesinlikle olmaz!",
                "Ne diyeceğimi bilmiyorum…",
                "Gerçekten mi?!",
                "Olmaz!!!",
                "Emin değilim...",
                "Fiyat 3.14.",
                "Toplantı saat 14,30."
            })
    void ends_sentence_on_a_real_terminator(final String text) {
        assertThat(SentenceBoundary.endsSentence(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Toplantıya Dr.",
                "Sunumu yapan Prof.",
                "Katılımcılar Sn.",
                "Kalem, defter vb.",
                "Rapor, sunum vs.",
                "Ayrıntı için bkz.",
                "Şirket unvanı A.Ş.",
                "Kurum kodu T.C.",
                "İmzalayan A.",
                "Gündemin 1."
            })
    void does_not_end_sentence_on_an_abbreviation_initial_or_ordinal(final String text) {
        assertThat(SentenceBoundary.endsSentence(text)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bugün toplantı var", "yarın devam ederiz", "sonra bakarız"})
    void does_not_end_sentence_without_punctuation(final String text) {
        assertThat(SentenceBoundary.endsSentence(text)).isFalse();
    }

    @Test
    void abbreviation_match_is_case_insensitive_in_turkish() {
        // Turkish locale: "VB." lower-cases to "vb", not "vb" via the dotted-I rule
        // that would break an English-locale comparison.
        assertThat(SentenceBoundary.endsSentence("kalem, defter VB.")).isFalse();
        assertThat(SentenceBoundary.endsSentence("ayrıntı için BKZ.")).isFalse();
    }

    @Test
    void a_word_that_merely_contains_an_abbreviation_still_ends_the_sentence() {
        // "no" is an abbreviation; "kano" is not. Matching must be on the whole token.
        assertThat(SentenceBoundary.endsSentence("Gölde bir kano.")).isTrue();
    }

    @Test
    void trailing_url_or_email_without_a_terminator_is_not_a_boundary() {
        assertThat(SentenceBoundary.endsSentence("adres https://ornek.com/rapor")).isFalse();
        assertThat(SentenceBoundary.endsSentence("bana yaz ali@ornek.com")).isFalse();
    }

    @Test
    void trailing_url_followed_by_a_full_stop_is_a_boundary() {
        assertThat(SentenceBoundary.endsSentence("adres https://ornek.com/rapor.")).isTrue();
    }

    @Test
    void blank_and_null_are_not_boundaries() {
        assertThat(SentenceBoundary.endsSentence(null)).isFalse();
        assertThat(SentenceBoundary.endsSentence("")).isFalse();
        assertThat(SentenceBoundary.endsSentence("   ")).isFalse();
    }

    @Test
    void trailing_whitespace_does_not_hide_the_terminator() {
        assertThat(SentenceBoundary.endsSentence("Teşekkür ederim.  \n")).isTrue();
    }
}
