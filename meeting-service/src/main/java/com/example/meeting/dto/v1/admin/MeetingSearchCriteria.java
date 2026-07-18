package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.MeetingStatus;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Validated server-side meeting-history filters for #867.
 *
 * <p>The date interval is half-open: {@code [dateFrom, dateTo)}. Filters are
 * combined with AND. A missing filter is allowed for backwards-compatible list
 * calls, while a supplied blank or malformed filter fails closed.
 */
public record MeetingSearchCriteria(
        MeetingStatus status,
        String title,
        UUID meetingId,
        Instant dateFrom,
        Instant dateTo) {

    static final int MIN_TITLE_LENGTH = 2;
    static final int MAX_TITLE_LENGTH = 200;

    public static MeetingSearchCriteria from(
            MeetingStatus status,
            String title,
            UUID meetingId,
            String dateFrom,
            String dateTo) {
        String normalizedTitle = normalizeTitle(title);
        Instant parsedDateFrom = parseInstant(dateFrom, "dateFrom");
        Instant parsedDateTo = parseInstant(dateTo, "dateTo");

        if ((parsedDateFrom == null) != (parsedDateTo == null)) {
            throw new IllegalArgumentException("dateFrom and dateTo must be supplied together.");
        }
        if (parsedDateFrom != null && !parsedDateFrom.isBefore(parsedDateTo)) {
            throw new IllegalArgumentException("dateFrom must be before dateTo.");
        }

        return new MeetingSearchCriteria(
                status, normalizedTitle, meetingId, parsedDateFrom, parsedDateTo);
    }

    /** Bounded-cardinality metric tag; never contains title text or identifiers. */
    public String metricFilterShape() {
        List<String> filters = new ArrayList<>(4);
        if (status != null) {
            filters.add("status");
        }
        if (title != null) {
            filters.add("title");
        }
        if (meetingId != null) {
            filters.add("meeting_id");
        }
        if (dateFrom != null) {
            filters.add("date_range");
        }
        return filters.isEmpty() ? "none" : String.join("+", filters);
    }

    private static String normalizeTitle(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() < MIN_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "title must contain at least " + MIN_TITLE_LENGTH + " characters.");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException(
                    "title must contain at most " + MAX_TITLE_LENGTH + " characters.");
        }
        return normalized;
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant.");
        }
    }
}
