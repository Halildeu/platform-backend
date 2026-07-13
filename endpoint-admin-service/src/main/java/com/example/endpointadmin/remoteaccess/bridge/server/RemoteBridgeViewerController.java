package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorIdentity;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyFrame;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyStreamAuthorizationRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerSubscription;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Faz 22.6 #1580 — the operator VIEW_ONLY screen viewer endpoint (owner-decided WEB MFE path; recording-OFF,
 * attended, 1:1 pilot). It is the operator-facing SSE tap over the slice-1 (#770) broker fan-out: the
 * {@link ViewOnlyViewerRegistry} already relays the agent's live {@code image/png} frames latest-wins (no WORM,
 * no buffer, no persistence). This controller authenticates + authorizes ONE operator and streams those frames
 * to the browser as Server-Sent Events. The browser uses {@code fetch} (NOT native EventSource) so it can carry
 * the {@code Authorization: Bearer} JWT the rest of the MFE already sends.
 *
 * <p><b>Disabled-by-default</b> ({@code remote-bridge.viewer.enabled=true}); also requires the global bridge gate.
 *
 * <p><b>Authorize as "an operator who HOLDS the active VIEW_ONLY stream".</b> Authenticate first
 * ({@link OperatorCredentialExtractor} → {@link OperatorAuthenticator}); then the session MUST be owned by the
 * operator's VERIFIED tenant AND subject, MUST be {@code ACTIVE}, AND the requested {@code streamId} (the
 * SCREEN_VIEW operation id) MUST be a LIVE broker-authorized VIEW_ONLY stream for that session's transport peer
 * ({@link ViewOnlyStreamAuthorizationRegistry#isAuthorized}). Every miss — not-found, not-owned, not-active,
 * no/expired stream — yields the SAME {@code 404} (no existence oracle). {@code 409} is returned ONLY after all
 * those pass, when a viewer already holds this session's 1:1 slot.
 *
 * <p><b>Backpressure-free + privacy.</b> The per-viewer subscription is latest-wins single-slot; a slow browser
 * never backs up the agent DATA stream and never grows a buffer. The stream ends (subscription removed, exactly
 * once) on disconnect, session-lifecycle close (detected within {@code WAKE_POLL_MILLIS}), or a terminal session
 * state. No screen bytes are persisted or logged; audit is metadata-only (the operator subject is logged only as
 * a fingerprint). Live (pilot) enablement additionally requires the fail-closed hash-chain audit slice.
 */
@RestController
@RequestMapping("/internal/remote-bridge/operator")
@ConditionalOnProperty(prefix = "remote-bridge.viewer", name = "enabled", havingValue = "true")
public class RemoteBridgeViewerController {

    private static final Logger log = LoggerFactory.getLogger(RemoteBridgeViewerController.class);

    private static final long HEARTBEAT_MILLIS = 15_000L;   // SSE keepalive comment cadence
    private static final long WAKE_POLL_MILLIS = 1_000L;    // close/terminal detection latency bound
    private static final long EMITTER_TIMEOUT_MILLIS = 0L;  // no servlet timeout; lifecycle/disconnect ends it
    private static final int MAX_CONCURRENT_VIEWERS = 64;   // bounds emit threads (each stream holds one)
    private static final Pattern STREAM_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final OperatorAuthenticator authenticator;
    private final RemoteBridgeSessionStore sessionStore;
    private final ViewOnlyViewerRegistry viewerRegistry;
    private final ViewOnlyStreamAuthorizationRegistry streamAuthorization;
    private final RemoteBridgeViewerAuditService viewerAudit;
    private final Counter viewerStarted;
    private final Counter viewerRejected;
    private final Counter viewerEnded;
    private final Counter viewerFramesSent;
    private final Counter viewerRenderAcksAccepted;
    private final Counter viewerRenderAcksRejected;
    private final DistributionSummary firstFrameAgeMillis;
    private final DistributionSummary steadyFrameAgeMillis;
    private final ThreadPoolExecutor emitPool;

    public RemoteBridgeViewerController(OperatorAuthenticator authenticator,
                                        RemoteBridgeSessionStore sessionStore,
                                        ViewOnlyViewerRegistry viewerRegistry,
                                        ViewOnlyStreamAuthorizationRegistry streamAuthorization,
                                        RemoteBridgeViewerAuditService viewerAudit,
                                        MeterRegistry meterRegistry) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.viewerRegistry = Objects.requireNonNull(viewerRegistry, "viewerRegistry");
        this.streamAuthorization = Objects.requireNonNull(streamAuthorization, "streamAuthorization");
        this.viewerAudit = Objects.requireNonNull(viewerAudit, "viewerAudit");
        this.viewerStarted = Counter.builder("remote_access_bridge_viewer_started_total")
                .description("VIEW_ONLY operator viewer streams started").register(meterRegistry);
        this.viewerRejected = Counter.builder("remote_access_bridge_viewer_rejected_total")
                .description("VIEW_ONLY operator viewer connections refused (unauth/not-owned/not-active/no-stream/bound/at-capacity)")
                .register(meterRegistry);
        this.viewerEnded = Counter.builder("remote_access_bridge_viewer_ended_total")
                .description("VIEW_ONLY operator viewer streams ended").register(meterRegistry);
        this.viewerFramesSent = Counter.builder("remote_access_bridge_viewer_frames_sent_total")
                .description("VIEW_ONLY frames handed to an authorized viewer SSE response").register(meterRegistry);
        this.viewerRenderAcksAccepted = Counter.builder("remote_access_bridge_viewer_render_ack_accepted_total")
                .description("Client-reported VIEW_ONLY browser render acknowledgements accepted by the broker")
                .register(meterRegistry);
        this.viewerRenderAcksRejected = Counter.builder("remote_access_bridge_viewer_render_ack_rejected_total")
                .description("Rejected unknown, stale, replayed or unauthorized browser render acknowledgements")
                .register(meterRegistry);
        this.firstFrameAgeMillis = frameAgeSummary(meterRegistry, "first");
        this.steadyFrameAgeMillis = frameAgeSummary(meterRegistry, "steady");
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "rb-viewer-emit-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        // bounded: at most MAX_CONCURRENT_VIEWERS emit threads; a SynchronousQueue + AbortPolicy means a viewer
        // beyond capacity is refused fast (503) rather than silently queued behind a never-ending stream.
        this.emitPool = new ThreadPoolExecutor(0, MAX_CONCURRENT_VIEWERS, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), tf, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        emitPool.shutdownNow();
    }

    /**
     * Stream a session's live VIEW_ONLY screen frames to the authorized operator as SSE. {@code streamId} is the
     * broker-authorized SCREEN_VIEW operation id; the stream MUST be live-authorized for the session peer. Events:
     * {@code meta} once at start; {@code frame} ({@code {seq,contentType,dataB64}}) per relayed frame; periodic
     * {@code :heartbeat} comments.
     */
    @GetMapping(path = "/sessions/{sessionId}/view", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter view(@PathVariable("sessionId") String sessionId,
                           @RequestParam(value = "streamId", required = false) String streamId,
                           HttpServletRequest request) {
        OperatorIdentity identity = authenticator.authenticate(OperatorCredentialExtractor.extract(request));
        if (identity == null || !identity.authenticated()) {
            viewerRejected.increment();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        // Single opaque 404 for a missing streamId, not-found, not-owned, not-active, and no/expired authorized
        // stream — no oracle (streamId is required=false so a missing param collapses here, not to a 400).
        Optional<RemoteBridgeSession> owned = ownedSession(sessionId, identity);
        if (!validStreamId(streamId) || owned.isEmpty() || !owned.get().state().isActive()
                || !streamAuthorization.isAuthorized(sessionId, streamId, owned.get().transportPeerKey(),
                        System.currentTimeMillis())) {
            viewerRejected.increment();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String deviceId = owned.get().deviceId();
        UUID operatorTenant = parseUuidOrNull(owned.get().operatorTenantId());
        String operatorSubject = identity.operatorSubject();

        // latest-wins single-slot subscription; empty Optional => the 1:1 viewer bound is already taken.
        Semaphore wake = new Semaphore(0);
        Optional<ViewOnlyViewerSubscription> maybeSub = viewerRegistry.subscribe(
                sessionId, streamId, identity.tenantId(), identity.operatorSubject(), wake::release);
        if (maybeSub.isEmpty()) {
            viewerRejected.increment();
            throw new ResponseStatusException(HttpStatus.CONFLICT); // a viewer already holds this 1:1 session
        }
        ViewOnlyViewerSubscription subscription = maybeSub.get();

        // FAIL-CLOSED hash-chain audit START (the pilot-enable HARD GATE): we hold the 1:1 slot but have NOT
        // emitted a single frame yet. The START audit commits (its own short, advisory-locked transaction)
        // BEFORE the emit loop is scheduled, so no screen byte is ever observed without a tamper-evident
        // REMOTE_SUPPORT_SCREEN_OBSERVATION:VIEW_START record. A throw => no observation (release the slot, 503).
        try {
            viewerAudit.recordViewStart(operatorTenant, operatorSubject, sessionId, deviceId, streamId);
        } catch (RuntimeException auditFailure) {
            viewerRegistry.unsubscribe(subscription);
            viewerRejected.increment();
            log.warn("viewer START audit failed — fail-closed, no observation session={} stream={}",
                    sessionId, streamId, auditFailure);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        AtomicLong delivered = new AtomicLong();
        AtomicBoolean ended = new AtomicBoolean(false);
        Runnable end = () -> {
            if (ended.compareAndSet(false, true)) {
                viewerRegistry.unsubscribe(subscription);
                viewerEnded.increment();
                // STOP audit is best-effort: the stream is already ending, so a failure is logged, not thrown
                // (the chain still has the authoritative VIEW_START; an absent STOP is detectable downstream).
                try {
                    viewerAudit.recordViewStop(operatorTenant, operatorSubject, sessionId, deviceId, streamId,
                            delivered.get(), subscription.renderedCount());
                } catch (RuntimeException stopFailure) {
                    log.error("viewer STOP audit failed session={} stream={}", sessionId, streamId, stopFailure);
                }
                log.info("viewer stream END session={} stream={} framesDelivered={}",
                        sessionId, streamId, delivered.get());
            }
        };
        emitter.onCompletion(end);
        emitter.onTimeout(end);
        emitter.onError(e -> end.run());

        viewerStarted.increment();
        try {
            emitPool.execute(() -> runEmitLoop(emitter, subscription, wake, sessionId, streamId, deviceId, delivered));
        } catch (RejectedExecutionException at) {
            end.run(); // unsubscribe + STOP audit + END metric (started==ended), then refuse
            viewerRejected.increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); // emit pool at capacity
        }
        // metadata-only app log (NO screen bytes); the operator subject is a fingerprint, not raw PII — the full
        // subject lives only in the fail-closed hash-chain audit recorded above.
        log.info("viewer stream START session={} stream={} deviceFp={} operatorTenant={} operatorFp={} recording=false attended=true VIEW_ONLY",
                sessionId, streamId, fingerprint(deviceId), identity.tenantId(), fingerprint(identity.operatorSubject()));
        return emitter;
    }

    private void runEmitLoop(SseEmitter emitter, ViewOnlyViewerSubscription subscription, Semaphore wake,
                             String sessionId, String streamId, String deviceId, AtomicLong delivered) {
        try {
            emitter.send(SseEmitter.event().name("meta").data(
                    "{\"sessionId\":\"" + esc(sessionId) + "\",\"streamId\":\"" + esc(streamId)
                            + "\",\"deviceId\":\"" + esc(deviceId)
                            + "\",\"viewerId\":\"" + esc(subscription.viewerId())
                            + "\",\"recording\":false,\"attended\":true,\"capability\":\"VIEW_ONLY\"}",
                    MediaType.APPLICATION_JSON));
            long lastHeartbeat = System.currentTimeMillis();
            while (!subscription.isClosed()) {
                // session terminal (KILL/CLOSE/LOCAL_ABORT) ends the stream fail-closed; the lifecycle ALSO closes
                // the subscription (isClosed() above), but re-check so a terminal that races the close still stops.
                if (sessionStore.bySessionId(sessionId).map(RemoteBridgeSession::isTerminal).orElse(true)) {
                    break;
                }
                boolean woke = wake.tryAcquire(WAKE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (woke) {
                    wake.drainPermits(); // collapse multiple offers — poll() returns the latest-wins frame
                    Optional<ViewOnlyFrame> frame = subscription.poll();
                    if (frame.isPresent()) {
                        ViewOnlyFrame f = frame.get();
                        String b64 = Base64.getEncoder().encodeToString(f.payload().toByteArray());
                        long sentAtEpochMillis = System.currentTimeMillis();
                        if (!subscription.markSent(f, sentAtEpochMillis)) {
                            break;
                        }
                        emitter.send(SseEmitter.event().name("frame").data(
                            "{\"seq\":" + f.frameSeq() + ",\"contentType\":\"" + esc(f.contentType())
                                        + "\",\"observedAtEpochMillis\":" + f.observedAtEpochMillis()
                                        + ",\"sentAtEpochMillis\":" + sentAtEpochMillis
                                        + ",\"dataB64\":\"" + b64 + "\"}", MediaType.APPLICATION_JSON));
                        delivered.incrementAndGet();
                        viewerFramesSent.increment();
                    }
                }
                if (System.currentTimeMillis() - lastHeartbeat >= HEARTBEAT_MILLIS) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = System.currentTimeMillis();
                }
            }
            emitter.complete();
        } catch (IOException io) {
            emitter.completeWithError(io); // browser disconnected — onError cleans up (idempotent)
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException re) {
            emitter.completeWithError(re);
        }
    }

    /**
     * Browser-to-broker observation telemetry. This endpoint carries no input/control data and cannot issue an
     * agent operation: it only acknowledges a frame already sent on this exact live viewer subscription.
     */
    @PostMapping(path = "/sessions/{sessionId}/view", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledgeRendered(@PathVariable("sessionId") String sessionId,
                                    @RequestParam(value = "streamId", required = false) String streamId,
                                    @RequestBody RenderAckRequest renderAck,
                                    HttpServletRequest request) {
        OperatorIdentity identity = authenticator.authenticate(OperatorCredentialExtractor.extract(request));
        if (identity == null || !identity.authenticated()) {
            viewerRenderAcksRejected.increment();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Optional<RemoteBridgeSession> owned = ownedSession(sessionId, identity);
        if (renderAck == null || !validStreamId(streamId) || owned.isEmpty()
                || !owned.get().state().isActive()
                || !streamAuthorization.isAuthorized(sessionId, streamId, owned.get().transportPeerKey(),
                        System.currentTimeMillis())) {
            rejectRenderAckNotFound();
        }
        ViewOnlyViewerSubscription.RenderAcknowledgement acknowledgement = viewerRegistry.acknowledgeRendered(
                sessionId, streamId, identity.tenantId(), identity.operatorSubject(),
                renderAck.viewerId(), renderAck.frameSeq(), System.currentTimeMillis())
                .orElseGet(() -> {
                    rejectRenderAckNotFound();
                    return null;
                });
        try {
            DistributionSummary summary = acknowledgement.firstRenderedFrame()
                    ? firstFrameAgeMillis : steadyFrameAgeMillis;
            summary.record(acknowledgement.endToEndAgeMillis());
            viewerRenderAcksAccepted.increment();
        } catch (RuntimeException metricFailure) {
            // The subscription/audit count is already authoritative. A telemetry backend fault must not turn an
            // accepted, de-duplicated ACK into a retrying client loop; the evidence verifier will fail the metric
            // delta cross-check instead of overstating acceptance.
            log.warn("viewer render-ack metric write failed session={} stream={}",
                    sessionId, streamId, metricFailure);
        }
    }

    private void rejectRenderAckNotFound() {
        viewerRenderAcksRejected.increment();
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static boolean validStreamId(String streamId) {
        return streamId != null && STREAM_ID.matcher(streamId).matches();
    }

    private static DistributionSummary frameAgeSummary(MeterRegistry registry, String phase) {
        return DistributionSummary.builder("remote_access_bridge_viewer_frame_age_millis")
                .description("Broker-observed to browser-render-ack VIEW_ONLY frame age")
                .baseUnit("milliseconds")
                .tag("phase", phase)
                .publishPercentileHistogram()
                .register(registry);
    }

    public record RenderAckRequest(String viewerId, long frameSeq) {}

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

    /** A stable, non-reversible operator-subject fingerprint for app logs (full subject stays in the audit slice). */
    private static String fingerprint(String subject) {
        if (subject == null || subject.isBlank()) {
            return "none";
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6); // 12 hex chars — correlatable, not reversible
        } catch (NoSuchAlgorithmException e) {
            return "fp-unavailable";
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
