package com.example.meeting.service;

import com.example.meeting.dto.v1.admin.MeetingSearchCriteria;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/** Metadata-only search telemetry. Raw titles, IDs and date values are never tags. */
@Service
public class MeetingHistorySearchMetrics {

    static final String SEARCH_COUNTER = "meeting_history_search_requests_total";

    private final MeterRegistry meterRegistry;

    public MeetingHistorySearchMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordSuccess(MeetingSearchCriteria criteria) {
        increment("success", criteria.metricFilterShape());
    }

    public void recordValidationDenied() {
        increment("validation_denied", "invalid");
    }

    private void increment(String outcome, String filters) {
        Counter.builder(SEARCH_COUNTER)
                .description("Meeting history search requests by outcome and filter shape")
                .tag("outcome", outcome)
                .tag("filters", filters)
                .register(meterRegistry)
                .increment();
    }
}
