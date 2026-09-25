package com.example.meeting.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Only service-private identities/keys reach this boundary, never browser input. */
public interface TeamsCalendarTransport {
    Organizer resolve(String issuer, String subject);
    Choices browse(UUID organizer, OffsetDateTime from, OffsetDateTime to);
    Schedule select(UUID organizer, UUID meeting, String eventId);
    Schedule status(UUID organizer, UUID meeting);
    void cancel(UUID organizer, UUID meeting);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Organizer(long userId, long companyId, String subject, UUID tenantId, UUID organizerId) {}
    record Choice(String eventId, String title, OffsetDateTime startsAt, OffsetDateTime endsAt) {}
    record Choices(List<Choice> items, boolean truncated) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Schedule(UUID meetingId, String state, OffsetDateTime startsAt, OffsetDateTime endsAt, String failure) {}
}
