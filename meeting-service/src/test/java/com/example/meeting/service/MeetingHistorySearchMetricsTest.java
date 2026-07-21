package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.meeting.dto.v1.admin.MeetingSearchCriteria;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeetingHistorySearchMetricsTest {

    @Test
    void recordsOnlyOutcomeAndFilterShapeWithoutRawSearchValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeetingHistorySearchMetrics metrics = new MeetingHistorySearchMetrics(registry);
        UUID meetingId = UUID.randomUUID();
        MeetingSearchCriteria criteria = MeetingSearchCriteria.from(
                null, "Confidential search text", meetingId, null, null);

        metrics.recordSuccess(criteria);
        metrics.recordValidationDenied();

        assertThat(registry.find(MeetingHistorySearchMetrics.SEARCH_COUNTER).counters())
                .hasSize(2)
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .noneSatisfy(tag -> assertThat(tag.getValue())
                                .contains("Confidential", meetingId.toString())));
        assertThat(registry.get(MeetingHistorySearchMetrics.SEARCH_COUNTER)
                .tags("outcome", "success", "filters", "title+meeting_id")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MeetingHistorySearchMetrics.SEARCH_COUNTER)
                .tags("outcome", "validation_denied", "filters", "invalid")
                .counter().count()).isEqualTo(1.0);
    }
}
