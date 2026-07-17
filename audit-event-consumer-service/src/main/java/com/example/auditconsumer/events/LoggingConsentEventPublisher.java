package com.example.auditconsumer.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Redaction-safe fallback; the relay remains disabled unless a real transport is enabled. */
@Component
@ConditionalOnMissingBean(ConsentEventPublisher.class)
public class LoggingConsentEventPublisher implements ConsentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingConsentEventPublisher.class);

    @Override
    public void publish(ConsentEventMessage event) {
        log.info("consent-event transport disabled eventType={} eventKey={} meetingId={} tenantId={}",
                event.eventType(), event.eventKey(), event.meetingId(), event.tenantId());
    }
}
