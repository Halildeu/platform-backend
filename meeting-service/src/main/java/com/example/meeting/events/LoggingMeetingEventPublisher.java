package com.example.meeting.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link MeetingEventPublisher} for BE-1d — Faz 24 (platform-ai#244).
 *
 * <p>Delivers each event by emitting a single structured, REDACTION-SAFE log line
 * (event key + type + aggregate/meeting/tenant identifiers only — never the
 * payload, which may carry an assignee subject / PII). This is the honest seam:
 * a real, observable side-effect that proves the poller pipeline end-to-end while
 * the concrete transport (Redis {@code meeting:events} stream or an internal HTTP
 * intent to notification-orchestrator) and the {@code #412} consumer are wired in
 * a separate slice.
 *
 * <p>{@code @ConditionalOnMissingBean} so a later transport implementation (or a
 * test double) transparently replaces it.
 */
@Component
@ConditionalOnMissingBean(MeetingEventPublisher.class)
public class LoggingMeetingEventPublisher implements MeetingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingMeetingEventPublisher.class);

    @Override
    public void publish(final MeetingEventMessage event) {
        // Redaction guard: identifiers only. The payload (assignee subject, etc.)
        // is NEVER logged — it stays inside the event body for the real transport.
        log.info("meeting-event published eventType={} eventKey={} aggregateId={} meetingId={} tenantId={}",
                event.eventType(), event.eventKey(), event.aggregateId(), event.meetingId(), event.tenantId());
    }
}
