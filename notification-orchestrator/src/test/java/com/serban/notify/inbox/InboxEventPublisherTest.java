package com.serban.notify.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.repository.NotificationInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * InboxEventPublisher unit test (Faz 23.3 PR-E.3 + Faz 23.4 PR-E.4 cross-pod).
 *
 * <p>Verifies recompute-and-publish flow + null/blank guard + cross-pod
 * branching (PG NOTIFY when enabled, ApplicationEventPublisher fallback
 * when disabled).
 */
class InboxEventPublisherTest {

    private ApplicationEventPublisher applicationEventPublisher;
    private NotificationInboxRepository inboxRepository;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private InboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        inboxRepository = mock(NotificationInboxRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();  // real — JSON marshalling tested
        publisher = new InboxEventPublisher(
            applicationEventPublisher, inboxRepository, jdbcTemplate, objectMapper
        );
        // Default: cross-pod enabled (production default)
        ReflectionTestUtils.setField(publisher, "crossPodEnabled", true);
    }

    // ─── Cross-pod path (default) ────────────────────────────────────────

    @Test
    void crossPodEnabledEmitsPgNotify() {
        when(inboxRepository.countUnreadBySubscriber("default", "sub-1")).thenReturn(5L);

        publisher.publishInboxUpdated("default", "sub-1");

        // Verify NOTIFY was issued via JdbcTemplate.execute with channel name +
        // JSON payload containing all fields
        verify(jdbcTemplate).execute(argThat((String sql) ->
            sql.startsWith("NOTIFY inbox_updated, '")
                && sql.contains("\"orgId\":\"default\"")
                && sql.contains("\"subscriberId\":\"sub-1\"")
                && sql.contains("\"unreadCount\":5")
        ));
        // Cross-pod: do NOT directly call applicationEventPublisher;
        // listener will deliver via LISTEN path
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void crossPodEnabledZeroUnreadCountStillNotifies() {
        when(inboxRepository.countUnreadBySubscriber("default", "sub-1")).thenReturn(0L);

        publisher.publishInboxUpdated("default", "sub-1");

        verify(jdbcTemplate).execute(contains("\"unreadCount\":0"));
    }

    @Test
    void crossPodNotifyDbErrorLogsAndReturnsCleanly() {
        when(inboxRepository.countUnreadBySubscriber("default", "sub-1")).thenReturn(3L);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("conn lost"))
            .when(jdbcTemplate).execute(anyString());

        // Defensive: should NOT propagate (state mutation already committed)
        publisher.publishInboxUpdated("default", "sub-1");

        verify(jdbcTemplate).execute(anyString());
        // No fallback to local event for DB error path (different from JSON error)
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    // ─── Single-pod fallback ─────────────────────────────────────────────

    @Test
    void crossPodDisabledFallsBackToLocalEventPublisher() {
        ReflectionTestUtils.setField(publisher, "crossPodEnabled", false);
        when(inboxRepository.countUnreadBySubscriber("default", "sub-1")).thenReturn(7L);

        publisher.publishInboxUpdated("default", "sub-1");

        verify(applicationEventPublisher).publishEvent(argThat((InboxUpdatedEvent ev) ->
            "default".equals(ev.orgId())
                && "sub-1".equals(ev.subscriberId())
                && ev.unreadCount() == 7L
        ));
        // Single-pod: NO PG NOTIFY (uses local Spring event bus)
        verifyNoInteractions(jdbcTemplate);
    }

    // ─── Validation guards ───────────────────────────────────────────────

    @Test
    void nullOrgIdSkipsPublish() {
        publisher.publishInboxUpdated(null, "sub-1");

        verifyNoInteractions(applicationEventPublisher);
        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(inboxRepository);
    }

    @Test
    void blankSubscriberIdSkipsPublish() {
        publisher.publishInboxUpdated("default", "");

        verifyNoInteractions(applicationEventPublisher);
        verifyNoInteractions(jdbcTemplate);
    }
}
