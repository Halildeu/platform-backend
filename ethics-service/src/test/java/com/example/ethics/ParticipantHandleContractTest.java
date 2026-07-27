package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ethics.config.EthicsProperties;
import com.example.ethics.security.ParticipantHandles;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-203/D — the properties a participant handle has to keep.
 *
 * <p>The platform had already decided that a Keycloak subject does not reach a browser;
 * the first version of case participants handed browsers a list of them. In a
 * whistleblowing product that is not a style question — a subject is stable across every
 * case and every other product, so one that escapes is a correlation key.
 *
 * <p>These pin the three things that make the replacement worth having. Without the
 * first it is not a replacement; without the second it is an org-wide correlation key
 * wearing a different name; without the third rotating the key would silently change
 * what old handles mean instead of retiring them.
 */
class ParticipantHandleContractTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID CASE_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CASE_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SUBJECT = "802f0658-c8f1-4c58-b897-efb048083c3e";

    private static ParticipantHandles handles(String key) {
        return new ParticipantHandles(new EthicsProperties(
                ORG, Duration.ofMinutes(15), 210_000, "aud", "role", true, 30, key));
    }

    private static final ParticipantHandles HANDLES =
            handles("0123456789abcdef0123456789abcdef0123456789abcdef");

    @Test
    @DisplayName("handle subject'i taşımaz — tarayıcıya giden şey geri çevrilemez")
    void theHandleDoesNotCarryTheSubject() {
        String handle = HANDLES.mint(ORG, CASE_A, SUBJECT);
        assertThat(handle).doesNotContain(SUBJECT);
        assertThat(handle).doesNotContain(SUBJECT.replace("-", ""));
        assertThat(handle).doesNotContain(ORG.toString());
        assertThat(handle).doesNotContain(CASE_A.toString());
    }

    /**
     * The property an org-stable handle would not have. Two handles for the same
     * colleague must not let anyone say "the same person is on both of these cases".
     */
    @Test
    @DisplayName("aynı kişi iki davada iki ilgisiz handle alır")
    void thesamePersonOnTwoCasesIsNotJoinable() {
        assertThat(HANDLES.mint(ORG, CASE_A, SUBJECT))
                .isNotEqualTo(HANDLES.mint(ORG, CASE_B, SUBJECT));
    }

    @Test
    @DisplayName("aynı dava ve kişi için handle kararlıdır — seçici çalışabilsin")
    void theHandleIsStableWithinACase() {
        assertThat(HANDLES.mint(ORG, CASE_A, SUBJECT))
                .isEqualTo(HANDLES.mint(ORG, CASE_A, SUBJECT));
    }

    @Test
    @DisplayName("org sınırı handle'a giriyor")
    void theOrgIsBoundIntoTheHandle() {
        assertThat(HANDLES.mint(ORG, CASE_A, SUBJECT))
                .isNotEqualTo(HANDLES.mint(OTHER_ORG, CASE_A, SUBJECT));
    }

    /**
     * Which field a value sits in has to matter. Today the two UUIDs are fixed-length
     * and the subject is followed by a constant, so no shift between fields is even
     * expressible — the length prefixes in {@code mint} are defence for a later field,
     * not a live collision, and this test does not pretend otherwise. What it does pin
     * is positional meaning: the same two UUIDs in the other order are a different
     * handle.
     */
    @Test
    @DisplayName("alanın yeri anlam taşır — org ve dava yer değiştiremez")
    void fieldPositionCarriesMeaning() {
        assertThat(HANDLES.mint(ORG, CASE_A, SUBJECT))
                .isNotEqualTo(HANDLES.mint(CASE_A, ORG, SUBJECT));
    }

    /** Rotating the key retires outstanding handles rather than reinterpreting them. */
    @Test
    @DisplayName("anahtar değişince eski handle çözülmez")
    void rotatingTheKeyRetiresOutstandingHandles() {
        String old = HANDLES.mint(ORG, CASE_A, SUBJECT);
        var rotated = handles("fedcba9876543210fedcba9876543210fedcba9876543210");
        assertThat(rotated.matches(old, ORG, CASE_A, SUBJECT))
                .as("eski handle yeni anahtarla eşleşmemeli")
                .isFalse();
    }

    @Test
    @DisplayName("handle sürümünü taşır")
    void theHandleCarriesItsVersion() {
        assertThat(HANDLES.mint(ORG, CASE_A, SUBJECT)).startsWith("v1.");
    }

    @Test
    @DisplayName("eşleştirme yalnız doğru üçlüde tutar")
    void matchingHoldsOnlyForTheRightTriple() {
        String handle = HANDLES.mint(ORG, CASE_A, SUBJECT);
        assertThat(HANDLES.matches(handle, ORG, CASE_A, SUBJECT)).isTrue();
        assertThat(HANDLES.matches(handle, ORG, CASE_B, SUBJECT)).isFalse();
        assertThat(HANDLES.matches(handle, OTHER_ORG, CASE_A, SUBJECT)).isFalse();
        assertThat(HANDLES.matches(handle, ORG, CASE_A, "someone-else")).isFalse();
        assertThat(HANDLES.matches(null, ORG, CASE_A, SUBJECT)).isFalse();
        assertThat(HANDLES.matches("", ORG, CASE_A, SUBJECT)).isFalse();
    }

    /**
     * Booting without a key would mint handles from a default, which is the same as no
     * scoping at all — and it would fail at the first assignment rather than at start.
     */
    @Test
    @DisplayName("anahtarsız veya kısa anahtarla servis açılmaz")
    void theServiceRefusesToStartWithoutAKey() {
        assertThatThrownBy(() -> handles(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("participant-handle-key");
        assertThatThrownBy(() -> handles("kisa"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
