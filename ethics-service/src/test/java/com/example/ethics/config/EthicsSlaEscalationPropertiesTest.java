package com.example.ethics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Faz 35 ES-301 — the escalation policy is owner-supplied, bounded, and fails at boot (#882). */
class EthicsSlaEscalationPropertiesTest {

    private static final Instant DUE = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    @DisplayName("yapılandırma yoksa politika kapalıdır ve tek seviye ihlal anındadır")
    void absentConfigurationIsDisabledWithOneLevelAtBreach() {
        var policy = new EthicsSlaEscalationProperties(false, null);
        assertThat(policy.enabled()).isFalse();
        assertThat(policy.steps()).containsExactly(Duration.ZERO);
        assertThat(policy.levels()).isEqualTo(1);
    }

    @Test
    @DisplayName("boş liste de tek seviyeye düşer — sıfır seviyeli bir politika politika değildir")
    void anEmptyListFallsBackToOneLevel() {
        assertThat(new EthicsSlaEscalationProperties(true, List.of()).steps())
                .containsExactly(Duration.ZERO);
    }

    @Test
    @DisplayName("beşten fazla seviye reddedilir")
    void moreThanFiveLevelsAreRefused() {
        var six = List.of(Duration.ZERO, Duration.ofDays(1), Duration.ofDays(2),
                Duration.ofDays(3), Duration.ofDays(4), Duration.ofDays(5));
        assertThatThrownBy(() -> new EthicsSlaEscalationProperties(true, six))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 5");
    }

    @Test
    @DisplayName("negatif adım reddedilir — son tarihten önce eskalasyon olmaz")
    void aNegativeStepIsRefused() {
        assertThatThrownBy(() -> new EthicsSlaEscalationProperties(true, List.of(Duration.ofHours(-1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    @DisplayName("null adım reddedilir")
    void aNullStepIsRefused() {
        var steps = Arrays.asList(Duration.ZERO, null);
        assertThatThrownBy(() -> new EthicsSlaEscalationProperties(true, steps))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("adımlar kesin artan olmalıdır")
    void stepsMustStrictlyIncrease() {
        assertThatThrownBy(() -> new EthicsSlaEscalationProperties(true,
                List.of(Duration.ZERO, Duration.ofDays(3), Duration.ofDays(3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly increasing");
    }

    @Test
    @DisplayName("90 günü aşan adım reddedilir")
    void aStepBeyondTheLongestLegalWindowIsRefused() {
        assertThatThrownBy(() -> new EthicsSlaEscalationProperties(true, List.of(Duration.ofDays(91))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    /** The arithmetic every level rests on: strictly after, never at. */
    @Test
    @DisplayName("son tarihin tam anında seviye 0; bir saniye sonra seviye 1; adım geçince seviye 2")
    void levelReachedIsStrictlyAfterEachThreshold() {
        var policy = new EthicsSlaEscalationProperties(true, List.of(Duration.ZERO, Duration.ofDays(3)));
        assertThat(policy.levelReached(DUE, DUE.minusSeconds(1))).isZero();
        assertThat(policy.levelReached(DUE, DUE)).isZero();
        assertThat(policy.levelReached(DUE, DUE.plusSeconds(1))).isEqualTo(1);
        assertThat(policy.levelReached(DUE, DUE.plus(Duration.ofDays(3)))).isEqualTo(1);
        assertThat(policy.levelReached(DUE, DUE.plus(Duration.ofDays(3)).plusSeconds(1))).isEqualTo(2);
        assertThat(policy.levelReached(DUE, DUE.plus(Duration.ofDays(60)))).isEqualTo(2);
    }

    /**
     * The precision contract: a step or deadline carrying nanoseconds is compared, and later
     * written, at microseconds. Otherwise Java calls a level reached that the row's CHECK
     * constraint refuses once the values are rounded.
     */
    @Test
    @DisplayName("nanosaniyeli adım ve son tarih mikrosaniyede karşılaştırılır — DB'nin gördüğü değerle")
    void nanosecondsInTheConfigurationAreComparedAtMicroseconds() {
        var policy = new EthicsSlaEscalationProperties(true, List.of(Duration.ofNanos(999)));
        // 999 ns after the deadline truncates to the deadline itself: level 1 at due + 1 µs.
        assertThat(policy.thresholdAt(DUE, 1)).isEqualTo(DUE);
        assertThat(policy.levelReached(DUE, DUE.plusNanos(999))).isZero();
        assertThat(policy.levelReached(DUE, DUE.plusNanos(1_000))).isEqualTo(1);
        // A deadline with nanoseconds in it is truncated the same way.
        assertThat(policy.levelReached(DUE.plusNanos(500), DUE.plusNanos(1_000))).isEqualTo(1);
        assertThat(policy.thresholdAt(DUE.plusNanos(500), 1)).isEqualTo(DUE);
    }

    @Test
    @DisplayName("son tarih bilinmiyorsa hiçbir seviyeye ulaşılmaz")
    void anUnknownDeadlineReachesNoLevel() {
        var policy = new EthicsSlaEscalationProperties(true, List.of(Duration.ZERO));
        assertThat(policy.levelReached(null, DUE)).isZero();
    }
}
