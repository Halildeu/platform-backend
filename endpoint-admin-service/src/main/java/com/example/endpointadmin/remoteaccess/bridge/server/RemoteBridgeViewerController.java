package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorIdentity;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyFrame;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerSubscription;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Faz 22.6 #1580 — the operator VIEW_ONLY screen viewer endpoint (owner-decided WEB MFE path; recording-OFF,
 * attended, 1:1 pilot). It is the operator-facing SSE tap over the slice-1 broker fan-out: the
 * {@link ViewOnlyViewerRegistry} already relays the agent's live {@code image/png} frames latest-wins (no WORM,
 * no buffer, no persistence); this controller authenticates + authorizes ONE operator and streams those frames
 * to the browser as Server-Sent Events. The browser uses {@code fetch} (NOT native EventSource) so it can carry
 * the {@code Authorization: Bearer} JWT the rest of the MFE already sends.
 *
 * <p><b>Disabled-by-default</b> ({@code remote-bridge.viewer.enabled=true}); also requires the global bridge gate.
 *
 * <p><b>Authorize like the operator REST.</b> Authenticate first ({@link OperatorCredentialExtractor} →
 * {@link OperatorAuthenticator}); then the session MUST be owned by the operator's VERIFIED tenant AND subject
 * (a not-found and a not-owned session both yield {@code 404} — no existence oracle), and MUST be {@code ACTIVE}
 * (only ACTIVE admits a VIEW_ONLY frame). The 1:1 viewer bound is enforced by the registry: a second viewer for
 * a session gets {@code 409}.
 *
 * <p><b>Backpressure-free + privacy.</b> The per-viewer subscription is latest-wins single-slot; a slow browser
 * never backs up the agent DATA stream and never grows a buffer. The stream ends (and the subscription is
 * removed) on disconnect, heartbeat-detected session terminal, or session lifecycle close (which closes viewers).
 * No screen bytes are persisted or logged; audit is metadata-only.
 */
@RestController
@RequestMapping("/internal/remote-bridge/operator")
@ConditionalOnProperty(prefix = "remote-bridge.viewer", name = "enabled", havingValue = "true")
public class RemoteBridgeViewerController {

    private static final Logger log = LoggerFactory.getLogger(RemoteBridgeViewerController.class);

    private static final long HEARTBEAT_MILLIS = 15_000L;
    private static final long EMITTER_TIMEOUT_MILLIS = 0L; // no servlet timeout; lifecycle/disconnect ends it

    private final OperatorAuthenticator authenticator;
    private final RemoteBridgeSessionStore sessionStore;
    private final ViewOnlyViewerRegistry viewerRegistry;
    private final Counter viewerStarted;
    private final Counter viewerRejected;
    private final Counter viewerEnded;
    private final ExecutorService emitPool;

    public RemoteBridgeViewerController(OperatorAuthenticator authenticator,
                                        RemoteBridgeSessionStore sessionStore,
                                        ViewOnlyViewerRegistry viewerRegistry,
                                        MeterRegistry meterRegistry) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.viewerRegistry = Objects.requireNonNull(viewerRegistry, "viewerRegistry");
        this.viewerStarted = Counter.builder("remote_access_bridge_viewer_started_total")
                .description("VIEW_ONLY operator viewer streams started").register(meterRegistry);
        this.viewerRejected = Counter.builder("remote_access_bridge_viewer_rejected_total")
                .description("VIEW_ONLY operator viewer connections refused (unauth/not-owned/not-active/bound)")
                .register(meterRegistry);
        this.viewerEnded = Counter.builder("remote_access_bridge_viewer_ended_total")
                .description("VIEW_ONLY operator viewer streams ended").register(meterRegistry);
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "rb-viewer-emit-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.emitPool = Executors.newCachedThreadPool(tf);
    }

    /**
     * Stream a session's live VIEW_ONLY screen frames to the authorized operator as SSE. Events: {@code meta}
     * (session id, device id, recording=false, attended=true, VIEW_ONLY, content-type) once at start; {@code
     * frame} ({@code {seq, contentType, dataB64}}) per relayed frame; periodic {@code :heartbeat} comments.
     */
    @GetMapping(path = "/sessions/{sessionId}/view", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter view(@PathVariable("sessionId") String sessionId, HttpServletRequest request) {
        OperatorIdentity identity = authenticator.authenticate(OperatorCredentialExtractor.extract(request));
        if (identity == null || !identity.authenticated()) {
            viewerRejected.increment();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Optional<RemoteBridgeSession> owned = ownedSession(sessionId, identity);
        if (owned.isEmpty()) {
            viewerRejected.increment();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // not-found AND not-owned: same, no oracle
        }
        if (!owned.get().state().isActive()) {
            viewerRejected.increment();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // not ACTIVE → no view, same opaque 404
        }
        String deviceId = owned.get().deviceId();

        // latest-wins single-slot subscription; empty Optional => the 1:1 viewer bound is already taken.
        Semaphore wake = new Semaphore(0);
        Optional<ViewOnlyViewerSubscription> maybeSub = viewerRegistry.subscribe(sessionId, wake::release);
        if (maybeSub.isEmpty()) {
            viewerRejected.increment();
            throw new ResponseStatusException(HttpStatus.CONFLICT); // a viewer already holds this 1:1 session
        }
        ViewOnlyViewerSubscription subscription = maybeSub.get();

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        AtomicLong delivered = new AtomicLong();
        // metadata-only audit (NO screen bytes). TODO(#1580 pilot-enable): route start/stop through the
        // fail-closed hash-chain EndpointAuditService (purpose REMOTE_SUPPORT_SCREEN_OBSERVATION) before live.
        viewerStarted.increment();
        log.info("viewer stream START session={} device={} operatorTenant={} operatorSubject={} recording=false attended=true VIEW_ONLY",
                sessionId, deviceId, identity.tenantId(), identity.operatorSubject());

        emitter.onCompletion(() -> endViewer(subscription, sessionId, delivered, "completion"));
        emitter.onTimeout(() -> endViewer(subscription, sessionId, delivered, "timeout"));
        emitter.onError(e -> endViewer(subscription, sessionId, delivered, "error"));

        emitPool.execute(() -> runEmitLoop(emitter, subscription, wake, sessionId, deviceId, delivered));
        return emitter;
    }

    private void runEmitLoop(SseEmitter emitter, ViewOnlyViewerSubscription subscription, Semaphore wake,
                             String sessionId, String deviceId, AtomicLong delivered) {
        try {
            emitter.send(SseEmitter.event().name("meta").data(
                    "{\"sessionId\":\"" + esc(sessionId) + "\",\"deviceId\":\"" + esc(deviceId)
                            + "\",\"recording\":false,\"attended\":true,\"capability\":\"VIEW_ONLY\""
                            + ",\"contentType\":\"image/png\"}", MediaType.APPLICATION_JSON));
            while (!subscription.isClosed()) {
                // session terminal (KILL/CLOSE/LOCAL_ABORT) ends the stream fail-closed; the lifecycle ALSO
                // closes the subscription, but re-check here so a terminal that races the close still stops us.
                if (sessionStore.bySessionId(sessionId).map(s -> s.isTerminal()).orElse(true)) {
                    break;
                }
                boolean woke = wake.tryAcquire(HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
                if (!woke) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    continue;
                }
                wake.drainPermits(); // collapse multiple offers — poll() returns the latest-wins frame
                Optional<ViewOnlyFrame> frame = subscription.poll();
                if (frame.isPresent()) {
                    ViewOnlyFrame f = frame.get();
                    String b64 = Base64.getEncoder().encodeToString(f.payload().toByteArray());
                    emitter.send(SseEmitter.event().name("frame").data(
                            "{\"seq\":" + f.frameSeq() + ",\"contentType\":\"" + esc(f.contentType())
                                    + "\",\"dataB64\":\"" + b64 + "\"}", MediaType.APPLICATION_JSON));
                    delivered.incrementAndGet();
                }
            }
            emitter.complete();
        } catch (IOException io) {
            emitter.completeWithError(io); // browser disconnected — onError cleans up
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException re) {
            emitter.completeWithError(re);
        }
    }

    private void endViewer(ViewOnlyViewerSubscription subscription, String sessionId, AtomicLong delivered,
                           String reason) {
        viewerRegistry.unsubscribe(subscription);
        viewerEnded.increment();
        log.info("viewer stream END session={} reason={} framesDelivered={}", sessionId, reason, delivered.get());
    }

    private Optional<RemoteBridgeSession> ownedSession(String sessionId, OperatorIdentity identity) {
        UUID tenantId = parseUuidOrNull(identity.tenantId());
        if (tenantId == null) {
            return Optional.empty();
        }
        String canonicalTenant = tenantId.toString();
        return sessionStore.bySessionId(sessionId)
                .filter(session -> canonicalTenant.equals(session.operatorTenantId())
                        && Objects.equals(session.operatorSubject(), identity.operatorSubject()));
    }

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
