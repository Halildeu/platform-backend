package com.example.auditconsumer.events;

/** Transport seam for a committed meeting.consent.revoked outbox row. */
public interface ConsentEventPublisher {
    void publish(ConsentEventMessage event);
}
