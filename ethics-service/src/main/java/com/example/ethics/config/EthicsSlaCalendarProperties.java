package com.example.ethics.config;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ES-301 — the organisation's working calendar, for the early-warning threshold only (#882).
 *
 * <p><strong>What this calendar must never touch:</strong> the statutory deadlines. EU
 * 2019/1937 counts seven days and three months in calendar time — the directive does not
 * pause for weekends, and neither does {@code CaseSlaClock}, whose arithmetic deliberately
 * has no parameter this class could reach ({@code CaseWaitingReasonTest} pins that). A
 * business calendar applied to the deadline itself would be the pause-that-moves-a-deadline
 * failure in different clothes: a breach made to disappear by configuration.
 *
 * <p>What it legitimately answers is <em>urgency</em>: "the deadline is Monday" means
 * something different on Friday than on Wednesday, because the days in between are days
 * nobody is at work. The warning threshold is therefore expressed in business days.
 *
 * <p><strong>Owner-supplied, absent by default.</strong> Holidays are jurisdiction- and
 * company-specific; a hardcoded list would be wrong for every tenant but one. With no
 * configuration the warning feature is off ({@code warnBusinessDays=0}) and nothing changes.
 */
@ConfigurationProperties(prefix = "ethics.sla.calendar")
public record EthicsSlaCalendarProperties(
        String zone,
        List<LocalDate> holidays,
        Set<DayOfWeek> weekendDays,
        int warnBusinessDays) {

    /** A wider window than this would warn on most of a feedback period — noise, not urgency. */
    static final int WARN_WINDOW_MAXIMUM = 30;

    public EthicsSlaCalendarProperties {
        zone = (zone == null || zone.isBlank()) ? "Europe/Istanbul" : zone;
        holidays = holidays == null ? List.of() : List.copyOf(holidays);
        weekendDays = (weekendDays == null || weekendDays.isEmpty())
                ? Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                : Set.copyOf(weekendDays);
        // An invalid zone must fail at startup, not at the first sweep at 03:00.
        ZoneId.of(zone);
        if (warnBusinessDays < 0 || warnBusinessDays > WARN_WINDOW_MAXIMUM) {
            throw new IllegalArgumentException(
                    "ethics.sla.calendar.warn-business-days must be 0.." + WARN_WINDOW_MAXIMUM
                            + " (0 disables the warning); got " + warnBusinessDays);
        }
        if (weekendDays.size() >= 7) {
            throw new IllegalArgumentException(
                    "ethics.sla.calendar.weekend-days cannot cover the whole week; "
                            + "no day would ever count and every warning window would be infinite");
        }
    }

    public boolean warningEnabled() {
        return warnBusinessDays > 0;
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
