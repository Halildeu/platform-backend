package com.example.ethics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ethics.config.EthicsSlaCalendarProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-301 — the working calendar feeds urgency, never the deadline (#882).
 *
 * <p>EU 2019/1937 counts seven days and three months in calendar time. A business calendar
 * that reached the deadline arithmetic would make breaches disappear by configuration — the
 * same failure the waiting-reason work refused, in different clothes. So the boundary is
 * asserted structurally here, alongside the arithmetic the calendar is actually for.
 */
class BusinessCalendarTest {

    private static EthicsSlaCalendarProperties config(List<LocalDate> holidays, int warnDays) {
        return new EthicsSlaCalendarProperties("Europe/Istanbul", holidays, null, warnDays);
    }

    private static Instant at(String isoDate) {
        // Midday in the configured zone, away from date-boundary edges.
        return Instant.parse(isoDate + "T09:00:00Z");
    }

    /**
     * <strong>The load-bearing boundary:</strong> the statutory clock never references this
     * calendar. Asserted against the source because the danger is a future edit — a runtime
     * test stays green right up until someone wires the calendar in, and then the failure
     * appears far from the line that caused it.
     */
    @Test
    @DisplayName("yasal saat iş takvimine hiç dokunmaz")
    void theStatutoryClockNeverTouchesTheBusinessCalendar() throws Exception {
        String clock = Files.readString(
                Path.of("src/main/java/com/example/ethics/service/CaseSlaClock.java"))
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
        assertThat(clock)
                .as("CaseSlaClock iş takvimine bağlanmış — yasal süre konfigürasyonla oynar hale gelir")
                .doesNotContain("BusinessCalendar")
                .doesNotContain("EthicsSlaCalendarProperties");
    }

    @Test
    @DisplayName("hafta sonu çalışma günü saymaz")
    void weekendsDoNotCount() {
        var calendar = new BusinessCalendar(config(List.of(), 2));
        // 2026-07-31 is a Friday; the Monday deadline is one business day away, not three.
        assertThat(calendar.businessDaysUntil(at("2026-07-31"), at("2026-08-03"))).isEqualTo(1);
        // Wednesday to Monday: Thu, Fri, Mon = 3.
        assertThat(calendar.businessDaysUntil(at("2026-07-29"), at("2026-08-03"))).isEqualTo(3);
    }

    @Test
    @DisplayName("resmî tatil çalışma günü saymaz")
    void holidaysDoNotCount() {
        // Declare the Thursday a holiday: Wednesday to Monday drops to Fri + Mon = 2.
        var calendar = new BusinessCalendar(config(List.of(LocalDate.parse("2026-07-30")), 2));
        assertThat(calendar.businessDaysUntil(at("2026-07-29"), at("2026-08-03"))).isEqualTo(2);
    }

    /** No working days left is the most urgent answer there is, and it is not an error. */
    @Test
    @DisplayName("bugün ya da geçmiş bir son tarih sıfır döner")
    void aDeadlineTodayOrPastAnswersZero() {
        var calendar = new BusinessCalendar(config(List.of(), 2));
        assertThat(calendar.businessDaysUntil(at("2026-07-29"), at("2026-07-29"))).isZero();
        assertThat(calendar.businessDaysUntil(at("2026-07-29"), at("2026-07-20"))).isZero();
    }

    /**
     * Days are compared in the organisation's zone. 23:00 UTC on Friday is already Saturday
     * in Istanbul — a calendar that counted in UTC would see one more working day than the
     * organisation has.
     */
    @Test
    @DisplayName("gün sınırı kurumun saat diliminde çizilir")
    void theDayBoundaryIsDrawnInTheOrganisationsZone() {
        var calendar = new BusinessCalendar(config(List.of(), 2));
        Instant lateFridayUtc = Instant.parse("2026-07-31T23:00:00Z"); // Sat 02:00 Istanbul
        Instant mondayNoon = Instant.parse("2026-08-03T09:00:00Z");
        // From Saturday (Istanbul), Monday is the only business day in range.
        assertThat(calendar.businessDaysUntil(lateFridayUtc, mondayNoon)).isEqualTo(1);
    }

    // ---------- configuration is owner-supplied and fail-closed ----------

    @Test
    @DisplayName("konfigürasyon yoksa uyarı kapalıdır")
    void withoutConfigurationTheWarningIsOff() {
        var defaults = new EthicsSlaCalendarProperties(null, null, null, 0);
        assertThat(defaults.warningEnabled()).isFalse();
        assertThat(defaults.zone()).isEqualTo("Europe/Istanbul");
        assertThat(defaults.weekendDays()).containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("geçersiz saat dilimi açılışta düşer, gece 03:00 taramasında değil")
    void anInvalidZoneFailsAtStartup() {
        assertThatThrownBy(() -> new EthicsSlaCalendarProperties("Mars/Olympus", null, null, 2))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("uyarı penceresi sınırın üstünde reddedilir")
    void anAbsurdWarnWindowIsRefused() {
        assertThatThrownBy(() -> new EthicsSlaCalendarProperties(null, null, null, 31))
                .hasMessageContaining("warn-business-days");
        assertThatThrownBy(() -> new EthicsSlaCalendarProperties(null, null, null, -1))
                .hasMessageContaining("warn-business-days");
    }

    /** Seven weekend days would make every window infinite; the constructor refuses. */
    @Test
    @DisplayName("bütün hafta hafta sonu ilan edilemez")
    void theWholeWeekCannotBeAWeekend() {
        assertThatThrownBy(() -> new EthicsSlaCalendarProperties(
                null, null, Set.of(DayOfWeek.values()), 2))
                .hasMessageContaining("weekend-days");
    }
}
