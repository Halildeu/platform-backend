package com.example.ethics.service;

import com.example.ethics.config.EthicsSlaCalendarProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ES-301 — counts the working days between now and a deadline (#882).
 *
 * <p>Consumed only by the early-warning path. The statutory arithmetic in
 * {@code CaseSlaClock} never sees this class — that separation is asserted structurally, so
 * a future edit that wires the calendar into the deadline fails a test rather than quietly
 * making breaches configurable.
 *
 * <p>Days are compared in the organisation's own zone: an instant late on Friday UTC may
 * already be Saturday in Istanbul, and a warning that fires a day late has missed the last
 * working day it existed to protect.
 */
@Component
public class BusinessCalendar {

    private final ZoneId zone;
    private final Set<LocalDate> holidays;
    private final EthicsSlaCalendarProperties properties;

    public BusinessCalendar(EthicsSlaCalendarProperties properties) {
        this.properties = properties;
        this.zone = properties.zoneId();
        this.holidays = Set.copyOf(properties.holidays());
    }

    /**
     * Business days strictly after {@code now}'s date, up to and including the deadline's
     * date. A deadline already past — or due later today — answers zero: there are no
     * working days left to spend, which is exactly what maximal urgency means.
     */
    public long businessDaysUntil(Instant now, Instant deadline) {
        LocalDate from = LocalDate.ofInstant(now, zone);
        LocalDate to = LocalDate.ofInstant(deadline, zone);
        long count = 0;
        for (LocalDate day = from.plusDays(1); !day.isAfter(to); day = day.plusDays(1)) {
            if (isWorkingDay(day)) count++;
        }
        return count;
    }

    private boolean isWorkingDay(LocalDate day) {
        return !properties.weekendDays().contains(day.getDayOfWeek()) && !holidays.contains(day);
    }
}
