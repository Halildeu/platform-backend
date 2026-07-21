package com.example.meeting.dto.v1.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.meeting.model.MeetingStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeetingSearchCriteriaTest {

    @Test
    void fromNormalizesFiltersAndExposesOnlyBoundedMetricShape() {
        UUID meetingId = UUID.randomUUID();

        MeetingSearchCriteria criteria = MeetingSearchCriteria.from(
                MeetingStatus.COMPLETED,
                "  Customer Review  ",
                meetingId,
                "2026-06-01T00:00:00Z",
                "2026-07-01T00:00:00Z");

        assertThat(criteria.title()).isEqualTo("Customer Review");
        assertThat(criteria.meetingId()).isEqualTo(meetingId);
        assertThat(criteria.dateFrom()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(criteria.dateTo()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(criteria.metricFilterShape())
                .isEqualTo("status+title+meeting_id+date_range")
                .doesNotContain("Customer", meetingId.toString(), "2026");
    }

    @Test
    void fromAllowsUnfilteredBackwardsCompatibleList() {
        MeetingSearchCriteria criteria = MeetingSearchCriteria.from(null, null, null, null, null);

        assertThat(criteria.metricFilterShape()).isEqualTo("none");
    }

    @Test
    void fromRejectsBlankShortLongOrMalformedFiltersWithoutEchoingValues() {
        assertThatThrownBy(() -> MeetingSearchCriteria.from(null, " ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MeetingSearchCriteria.from(null, "x", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MeetingSearchCriteria.from(
                null, "x".repeat(201), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MeetingSearchCriteria.from(
                null, null, null, "not-a-secret-search-value", "2026-07-01T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dateFrom must be an ISO-8601 instant.")
                .hasMessageNotContaining("not-a-secret-search-value");
    }

    @Test
    void fromRequiresAForwardHalfOpenDateRange() {
        assertThatThrownBy(() -> MeetingSearchCriteria.from(
                null, null, null, "2026-06-01T00:00:00Z", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supplied together");
        assertThatThrownBy(() -> MeetingSearchCriteria.from(
                null,
                null,
                null,
                "2026-07-01T00:00:00Z",
                "2026-07-01T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before dateTo");
    }
}
