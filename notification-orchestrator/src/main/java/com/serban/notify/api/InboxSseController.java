package com.serban.notify.api;

import com.serban.notify.inbox.InboxService;
import com.serban.notify.inbox.InboxUpdatedEvent;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Inbox Server-Sent Events (SSE) endpoint for real-time badge updates
 * (Faz 23.3 PR-E.3 — charter §187 "WS endpoint — unread count badge update").
 *
 * <p>Why SSE instead of WebSocket/SockJS/STOMP per charter literal? The
 * acceptance criterion (§204) is "unread count badge update" — server-to-client
 * unidirectional push. SSE satisfies this via plain HTTP (no extra dependency,
 * no protocol negotiation, no upgrade handshake). Bidirectional WebSocket
 * deferred to PR-F if client→server push (e.g. typing indicators) becomes
 * required.
 *
 * <p>Endpoint: {@code GET /api/v1/notify/inbox/me/stream}
 * <ul>
 *   <li>Initial event: current unread count</li>
 *   <li>Subsequent events: pushed when {@link InboxUpdatedEvent} fires for
 *       this subscriber (row insert, mark-read, archive)</li>
 *   <li>Heartbeat: every 25s — prevents intermediary proxies from idle-closing</li>
 * </ul>
 *
 * <p>Identity: {@code X-Org-Id} + {@code X-Subscriber-Id} headers (PR2 baseline
 * parity; JWT subject claim extraction PR-D-future).
 *
 * <p>Single-pod scope (intentional limitation): emitter map is per-JVM.
 * Cross-pod broadcast (Redis pub/sub or STOMP+broker) deferred to PR-E.4 /
 * 23.4. Current HPA min=1 in test, single replica → no observable issue.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Connect → emitter added to {@link #emitters} keyed by (org, subscriber)</li>
 *   <li>Disconnect (timeout / error / client close) → emitter removed</li>
 *   <li>{@link #cleanupStale()} every 60s sweeps any stale entries (defensive)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/notify/inbox")
@Validated
public class InboxSseController {

    private static final Logger log = LoggerFactory.getLogger(InboxSseController.class);

    /** SSE timeout — long-lived connection (30 min). */
    private static final long SSE_TIMEOUT_MS = 30L * 60 * 1000;

    /** Heartbeat cadence — proxies typically idle-timeout at 30-60s. */
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;

    private final InboxService inboxService;

    /** Per-(orgId, subscriberId) connected SSE emitters. */
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public InboxSseController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    /**
     * Subscribe to inbox events for the authenticated subscriber.
     */
    @GetMapping(path = "/me/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        String key = key(callerOrgId, subscriberId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitters.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(key, emitter));
        emitter.onTimeout(() -> {
            log.debug("inbox SSE timeout: key={}", key);
            removeEmitter(key, emitter);
        });
        emitter.onError(t -> {
            log.debug("inbox SSE error: key={} err={}", key, t.getMessage());
            removeEmitter(key, emitter);
        });

        // Initial event: current unread count
        try {
            long unreadCount = inboxService.unreadCount(callerOrgId, subscriberId);
            emitter.send(SseEmitter.event()
                .name("unread-count")
                .data(Map.of("unreadCount", unreadCount)));
        } catch (IOException e) {
            log.warn("inbox SSE initial send failed: key={} err={}", key, e.getMessage());
            emitter.completeWithError(e);
            removeEmitter(key, emitter);
        }

        log.info("inbox SSE subscribed: orgId={} subscriberId={} totalEmitters={}",
            callerOrgId, subscriberId, totalEmitters());

        return emitter;
    }

    /**
     * Listen for inbox state changes; broadcast to subscriber's connected SSE
     * clients on this pod.
     *
     * <p>{@link Async} so the event-publishing transaction doesn't block on
     * SSE network IO.
     */
    @EventListener
    @Async
    public void onInboxUpdated(InboxUpdatedEvent event) {
        String key = key(event.orgId(), event.subscriberId());
        List<SseEmitter> targets = emitters.get(key);
        if (targets == null || targets.isEmpty()) return;

        Map<String, Object> payload = Map.of("unreadCount", event.unreadCount());
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event()
                    .name("unread-count")
                    .data(payload));
            } catch (IOException e) {
                log.debug("inbox SSE event send failed (emitter dropping): key={} err={}",
                    key, e.getMessage());
                emitter.completeWithError(e);
                removeEmitter(key, emitter);
            }
        }
    }

    /**
     * Heartbeat sweep — push comment line to all emitters every 25s. Keeps
     * intermediary proxies from idle-closing.
     */
    @Scheduled(fixedDelay = HEARTBEAT_INTERVAL_MS)
    public void heartbeat() {
        emitters.forEach((key, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                    removeEmitter(key, emitter);
                }
            }
        });
    }

    /** Periodic stale entry cleanup (defensive — emitter callbacks should handle most). */
    @Scheduled(fixedDelay = 60_000L)
    public void cleanupStale() {
        emitters.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private void removeEmitter(String key, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(key);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(key);
            }
        }
    }

    private static String key(String orgId, String subscriberId) {
        return orgId + "::" + subscriberId;
    }

    /** Diagnostic accessor (test-only); count of connected emitters across all keys. */
    int totalEmitters() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }

    /** Test-only: clear all emitters (used in @AfterEach to isolate tests). */
    void clearAllForTest() {
        emitters.clear();
    }
}
