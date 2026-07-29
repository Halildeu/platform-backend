package com.example.ethics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ethics.config.EthicsSlaProperties;
import com.example.ethics.service.CaseSlaClock.AcknowledgementState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-301 — the seven-day acknowledgement obligation, computed where it can be
 * queried (#882).
 *
 * <p>It used to be computed in the browser from {@code createdAt}, which made it real only
 * while a case was open on screen. Measured on the test cell: 167 cases, 32 acknowledged,
 * <strong>49 already past seven days</strong> — and no surface reported it.
 */
class CaseSlaClockTest {

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    private CaseSlaClock clockAt(Instant now) {
        return new CaseSlaClock(
                new EthicsSlaProperties(Duration.ofDays(7), Duration.ofDays(90)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /**
     * The distinction the whole thing exists for. Both cases are unacknowledged; only one
     * is a breach, and a list that shows them alike is a list a handler reads as calm.
     */
    @Test
    @DisplayName("teyit verilmemiş iki vaka aynı görünmez: biri süre içinde, biri ihlal")
    void pendingAndBreachedAreNotTheSameState() {
        var clock = clockAt(NOW);

        var stillInWindow = clock.acknowledgement(NOW.minus(Duration.ofDays(3)), null);
        var pastWindow = clock.acknowledgement(NOW.minus(Duration.ofDays(9)), null);

        assertThat(stillInWindow.state()).isEqualTo(AcknowledgementState.PENDING);
        assertThat(pastWindow.state())
                .as("dokuz gün önce açılmış, teyit yok — bu bir ihlal")
                .isEqualTo(AcknowledgementState.BREACHED);
    }

    /** The boundary is the boundary: at exactly seven days nothing has been breached yet. */
    @Test
    @DisplayName("tam yedinci günde henüz ihlal yok")
    void theDeadlineIsNotBreachedUntilItPasses() {
        var clock = clockAt(NOW);
        assertThat(clock.acknowledgement(NOW.minus(Duration.ofDays(7)), null).state())
                .isEqualTo(AcknowledgementState.PENDING);
        assertThat(clock.acknowledgement(NOW.minus(Duration.ofDays(7)).minusSeconds(1), null).state())
                .isEqualTo(AcknowledgementState.BREACHED);
    }

    /**
     * A late acknowledgement still satisfies the obligation to acknowledge — it does not
     * un-happen. Recording the lateness separately keeps both facts readable instead of
     * hiding a three-week delay behind a green state.
     */
    @Test
    @DisplayName("geç verilen teyit yükümlülüğü karşılar ama geç olduğu kaybolmaz")
    void alatenessIsRecordedWithoutErasingTheAcknowledgement() {
        var clock = clockAt(NOW);
        Instant created = NOW.minus(Duration.ofDays(30));

        var late = clock.acknowledgement(created, created.plus(Duration.ofDays(21)));
        var onTime = clock.acknowledgement(created, created.plus(Duration.ofDays(2)));

        assertThat(late.state()).isEqualTo(AcknowledgementState.MET);
        assertThat(late.wasLate()).as("üç hafta gecikme kayboldu").isTrue();
        assertThat(onTime.state()).isEqualTo(AcknowledgementState.MET);
        assertThat(onTime.wasLate()).isFalse();
    }

    /**
     * Absent input reports absence. A case shown as PENDING because its timestamp was
     * missing reads as "fine" and is the one nobody chases.
     */
    @Test
    @DisplayName("hesaplanamayan durum, süre içindeymiş gibi gösterilmez")
    void whatCannotBeComputedIsNotReportedAsHealthy() {
        var result = clockAt(NOW).acknowledgement(null, null);
        assertThat(result.state()).isEqualTo(AcknowledgementState.UNKNOWN);
        assertThat(result.dueAt()).isNull();
    }

    /**
     * BREACHED alone is binary, and a handler holding forty-nine breached cases cannot act
     * on that. Distance past the deadline is what makes the list orderable.
     */
    @Test
    @DisplayName("ihlalin ne kadar aşıldığı ölçülür, ikili değil")
    void anOverdueCaseReportsHowFarPastItIs() {
        var clock = clockAt(NOW);
        var oneDay = clock.acknowledgement(NOW.minus(Duration.ofDays(8)), null);
        var threeWeeks = clock.acknowledgement(NOW.minus(Duration.ofDays(28)), null);

        assertThat(oneDay.overdueBy()).isEqualTo(Duration.ofDays(1));
        assertThat(threeWeeks.overdueBy()).isEqualTo(Duration.ofDays(21));
        assertThat(threeWeeks.overdueBy()).isGreaterThan(oneDay.overdueBy());
    }

    /**
     * Null, not zero. "Not overdue" and "overdue by nothing" are different claims, and a
     * zero would sort alongside genuinely-just-breached cases.
     */
    @Test
    @DisplayName("ihlal yoksa aşım süresi yok — sıfır değil")
    void nothingOverdueReportsNoDistanceRatherThanZero() {
        var clock = clockAt(NOW);
        assertThat(clock.acknowledgement(NOW.minus(Duration.ofDays(3)), null).overdueBy()).isNull();
        assertThat(clock.acknowledgement(NOW.minus(Duration.ofDays(30)),
                NOW.minus(Duration.ofDays(29))).overdueBy()).isNull();
        assertThat(clock.acknowledgement(null, null).overdueBy()).isNull();
    }

    @Test
    @DisplayName("son tarih açılış anından hesaplanır")
    void theDeadlineIsDerivedFromWhenTheCaseWasOpened() {
        Instant created = Instant.parse("2026-07-01T08:30:00Z");
        assertThat(clockAt(NOW).acknowledgement(created, null).dueAt())
                .isEqualTo(Instant.parse("2026-07-08T08:30:00Z"));
    }

    /**
     * The second obligation, EU 2019/1937 art. 9(1)(f): feedback within three months. It is
     * separate from acknowledgement and can disagree with it — a case acknowledged on day
     * one can still owe feedback on day ninety-one.
     */
    @Test
    @DisplayName("geri bildirim yükümlülüğü teyitten ayrı ilerler")
    void feedbackIsATrackedSeparatelyFromAcknowledgement() {
        var clock = clockAt(NOW);
        Instant created = NOW.minus(Duration.ofDays(100));

        var ack = clock.acknowledgement(created, created.plusSeconds(60));
        var feedback = clock.feedback(created, null);

        assertThat(ack.state()).isEqualTo(AcknowledgementState.MET);
        assertThat(feedback.state())
                .as("ilk gün teyit verilmiş olması yüz gün sonra geri bildirim borcunu kapatmaz")
                .isEqualTo(CaseSlaClock.FeedbackState.BREACHED);
    }

    /**
     * Closure is what satisfies it, because closing requires a reporter-facing message about
     * what was found. A case still open at ninety-one days owes feedback whatever else has
     * happened on it.
     */
    @Test
    @DisplayName("kapanış geri bildirimi karşılar, açık kalmak karşılamaz")
    void closureSatisfiesFeedbackAndStayingOpenDoesNot() {
        var clock = clockAt(NOW);
        Instant created = NOW.minus(Duration.ofDays(100));

        assertThat(clock.feedback(created, NOW.minus(Duration.ofDays(5))).state())
                .isEqualTo(CaseSlaClock.FeedbackState.MET);
        assertThat(clock.feedback(created, null).state())
                .isEqualTo(CaseSlaClock.FeedbackState.BREACHED);
        assertThat(clock.feedback(NOW.minus(Duration.ofDays(10)), null).state())
                .isEqualTo(CaseSlaClock.FeedbackState.PENDING);
    }

    /** A closure after the deadline still concludes the case; the delay is kept readable. */
    @Test
    @DisplayName("geç kapanış geri bildirimi karşılar ama gecikme kaydedilir")
    void aLateClosureIsStillFeedbackAndTheDelaySurvives() {
        var clock = clockAt(NOW);
        Instant created = NOW.minus(Duration.ofDays(200));
        var late = clock.feedback(created, created.plus(Duration.ofDays(120)));
        assertThat(late.state()).isEqualTo(CaseSlaClock.FeedbackState.MET);
        assertThat(late.wasLate()).isTrue();
    }

    /**
     * The channel may promise to answer sooner than the law requires. It may not promise
     * to answer later — a config that allowed thirty days would turn a legal maximum into
     * a setting and make the breach invisible by configuration rather than by oversight.
     */
    @Test
    @DisplayName("yasal azami süre bir ayara indirgenemez")
    void aLongerDeadlineThanTheLawAllowsIsRefusedAtStartup() {
        assertThatThrownBy(() -> new EthicsSlaProperties(Duration.ofDays(30), Duration.ofDays(90)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the legal maximum");

        assertThatThrownBy(() -> new EthicsSlaProperties(Duration.ofDays(7), Duration.ofDays(365)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the legal maximum");
    }

    @Test
    @DisplayName("daha kısa bir söz verilebilir")
    void aStricterPromiseThanTheLawIsAccepted() {
        var strict = new EthicsSlaProperties(Duration.ofDays(2), Duration.ofDays(30));
        assertThat(strict.acknowledgementWithin()).isEqualTo(Duration.ofDays(2));
    }

    /** A zero or negative window is not a stricter promise; it is a broken one. */
    @Test
    @DisplayName("sıfır süre bir son tarih değildir")
    void aZeroWindowIsRefused() {
        assertThatThrownBy(() -> new EthicsSlaProperties(Duration.ZERO, Duration.ofDays(90)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    /** Unset falls back to the legal maximum rather than to no deadline at all. */
    @Test
    @DisplayName("ayar verilmezse yasal azami süre uygulanır")
    void anUnsetDeadlineFallsBackToTheLegalMaximum() {
        var defaults = new EthicsSlaProperties(null, null);
        assertThat(defaults.acknowledgementWithin()).isEqualTo(Duration.ofDays(7));
        assertThat(defaults.feedbackWithin()).isEqualTo(Duration.ofDays(90));
    }
}
