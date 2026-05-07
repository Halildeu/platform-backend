package com.serban.notify.inbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.serban.notify.repository.NotificationInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * InboxEventPublisher — emits {@link InboxUpdatedEvent} after subscriber
 * inbox state mutation (Faz 23.3 PR-E.3 + Faz 23.4 PR-E.4 cross-pod).
 *
 * <p>Recomputes unread count via {@link NotificationInboxRepository#countUnreadBySubscriber}
 * before publishing — listener (SSE controller) pushes fresh count to client.
 *
 * <p>Tradeoff: one extra COUNT query per state mutation. Index
 * {@code idx_inbox_unread_badge} (V9 partial index WHERE state = UNREAD)
 * makes this O(unread rows for subscriber) — typically small.
 *
 * <p><b>Faz 23.4 PR-E.4 cross-pod broadcast</b>: PostgreSQL
 * {@code LISTEN/NOTIFY} pattern. Publisher emits {@code NOTIFY inbox_updated,
 * '<json>'} which all pods (including the originating pod) pick up via
 * {@link InboxNotifyListener}. The listener then re-emits a Spring
 * {@link InboxUpdatedEvent}, which {@link com.serban.notify.api.InboxSseController}
 * forwards to its locally-connected SSE clients. This unifies the cross-pod
 * delivery path: one publish → all pods broadcast to their clients.
 *
 * <p>Why PG LISTEN/NOTIFY (not Redis pub/sub or STOMP+broker)? ADR-0002 §7.1
 * mandates PG-only stateful infrastructure (Mongo/Redis/RabbitMQ YASAK).
 * PG LISTEN/NOTIFY is built-in, transactional (NOTIFY rolls back if
 * transaction does), and adds zero infra dependency. Payload limit 8000
 * bytes per NOTIFY (we send tiny JSON ~80 bytes).
 *
 * <p>Cross-pod activation toggled by {@code notify.inbox.cross-pod-enabled}
 * (default true). When disabled (single-pod test / local dev), publisher
 * falls back to legacy {@link org.springframework.context.ApplicationEventPublisher}
 * path so existing SSE behavior is preserved.
 */
@Component
public class InboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InboxEventPublisher.class);

    /** PG NOTIFY channel name — must match {@link InboxNotifyListener#CHANNEL}. */
    public static final String NOTIFY_CHANNEL = "inbox_updated";

    private final org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    private final NotificationInboxRepository inboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notify.inbox.cross-pod-enabled:true}")
    private boolean crossPodEnabled;

    public InboxEventPublisher(
        org.springframework.context.ApplicationEventPublisher applicationEventPublisher,
        NotificationInboxRepository inboxRepository,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.inboxRepository = inboxRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Recompute unread count + emit event. Caller passes (orgId, subscriberId)
     * for the affected subscriber.
     *
     * <p>Cross-pod path (default): {@code NOTIFY inbox_updated, '<json>'} —
     * picked up by all pods' {@link InboxNotifyListener}, which re-emits the
     * Spring event locally for SSE broadcast.
     *
     * <p>Single-pod fallback ({@code notify.inbox.cross-pod-enabled=false}):
     * direct {@link org.springframework.context.ApplicationEventPublisher#publishEvent}
     * — only this JVM's SSE clients see the event. Used for local dev / unit
     * tests where PG LISTEN/NOTIFY infrastructure is overkill.
     */
    public void publishInboxUpdated(String orgId, String subscriberId) {
        if (orgId == null || orgId.isBlank()) return;
        if (subscriberId == null || subscriberId.isBlank()) return;

        long unreadCount = inboxRepository.countUnreadBySubscriber(orgId, subscriberId);
        InboxUpdatedEvent event = new InboxUpdatedEvent(orgId, subscriberId, unreadCount);

        if (crossPodEnabled) {
            publishViaPgNotify(event);
        } else {
            log.debug("inbox event (local-only): orgId={} subscriberId={} unreadCount={}",
                orgId, subscriberId, unreadCount);
            applicationEventPublisher.publishEvent(event);
        }
    }

    /**
     * Emit {@code NOTIFY inbox_updated, '<json>'} so all pods (including this
     * one) deliver via their {@link InboxNotifyListener} → Spring event chain.
     *
     * <p>Transactional semantics: {@code pg_notify} via JdbcTemplate runs in
     * the current transaction (if any). Caller transaction rollback ⇒
     * NOTIFY also rolled back (PG built-in behavior). No phantom events.
     *
     * <p>Failure isolation: NOTIFY failure (e.g. DB connection lost) is
     * logged but does NOT propagate — inbox state mutation succeeded; SSE
     * push best-effort, client can poll {@code /inbox/me/unread-count}
     * fallback. Returning to caller without exception preserves application
     * semantic (state changed; just notification path lost).
     */
    private void publishViaPgNotify(InboxUpdatedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "orgId", event.orgId(),
                "subscriberId", event.subscriberId(),
                "unreadCount", event.unreadCount()
            ));
            // Single-quote escape for SQL literal; payload is JSON without
            // single-quotes typically, but defensive replace for safety.
            String escaped = payload.replace("'", "''");
            jdbcTemplate.execute("NOTIFY " + NOTIFY_CHANNEL + ", '" + escaped + "'");
            log.debug("inbox NOTIFY: orgId={} subscriberId={} unreadCount={} bytes={}",
                event.orgId(), event.subscriberId(), event.unreadCount(), payload.length());
        } catch (JsonProcessingException jpe) {
            log.warn("inbox NOTIFY skip (json marshal): {} — falling back to local event",
                jpe.getMessage());
            // Fall back to local-only event
            applicationEventPublisher.publishEvent(event);
        } catch (org.springframework.dao.DataAccessException dae) {
            log.warn("inbox NOTIFY skip (db error): {} — SSE push lost (clients can poll)",
                dae.getMessage());
            // Defensive: do NOT propagate. State mutation already committed
            // (separate transaction); SSE push best-effort.
        }
    }
}
