package com.example.endpointadmin.remoteaccess.bridge.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.AuthMethod;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorIdentity;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyStreamAuthorizationRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyFrame;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerSubscription;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerSubscription.RenderAcknowledgement;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Authz gates for the VIEW_ONLY operator viewer (the security-critical part): authenticate-first, owned-session
 * (tenant+subject), a LIVE broker-authorized VIEW_ONLY stream, ACTIVE-only — each miss a no-oracle 404 — and the
 * 1:1 viewer bound (409). (SSE streaming itself is browser-verified.)
 */
class RemoteBridgeViewerControllerTest {

    private static final String SESSION = "sess-1";
    private static final String STREAM = "op-1";
    private static final String PEER = "peer-thumb-1";
    private static final String TENANT = UUID.randomUUID().toString();
    private static final String SUBJECT = "operator@example.com";

    private final OperatorAuthenticator authenticator = mock(OperatorAuthenticator.class);
    private final RemoteBridgeSessionStore sessionStore = mock(RemoteBridgeSessionStore.class);
    private final ViewOnlyViewerRegistry registry = mock(ViewOnlyViewerRegistry.class);
    private final ViewOnlyStreamAuthorizationRegistry streamAuth = mock(ViewOnlyStreamAuthorizationRegistry.class);
    private final RemoteBridgeViewerAuditService viewerAudit = mock(RemoteBridgeViewerAuditService.class);
    private final RemoteBridgeViewerController controller = new RemoteBridgeViewerController(
            authenticator, sessionStore, registry, streamAuth, viewerAudit, new SimpleMeterRegistry());
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private void authedAs(String tenant, String subject) {
        when(authenticator.authenticate(any()))
                .thenReturn(OperatorIdentity.of(tenant, subject, AuthMethod.JWT_BEARER));
    }

    private RemoteBridgeSession session(String tenant, String subject, State state) {
        RemoteBridgeSession s = mock(RemoteBridgeSession.class);
        when(s.operatorTenantId()).thenReturn(tenant);
        when(s.operatorSubject()).thenReturn(subject);
        when(s.state()).thenReturn(state);
        when(s.deviceId()).thenReturn("device-1");
        when(s.transportPeerKey()).thenReturn(PEER);
        return s;
    }

    private void authorizedStream() {
        when(streamAuth.isAuthorized(eq(SESSION), eq(STREAM), eq(PEER), anyLong())).thenReturn(true);
    }

    @Test
    void unauthenticatedIsRejected401() {
        when(authenticator.authenticate(any())).thenReturn(OperatorIdentity.unauthenticated());
        assertThatThrownBy(() -> controller.view(SESSION, STREAM, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("401 UNAUTHORIZED");
        verify(registry, never()).reserve(any(), any(), any(), any(), any());
    }

    @Test
    void notOwnedSessionIs404NoOracle() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession other = session(UUID.randomUUID().toString(), SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> controller.view(SESSION, STREAM, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
        verify(registry, never()).reserve(any(), any(), any(), any(), any());
    }

    @Test
    void missingSessionIs404() {
        authedAs(TENANT, SUBJECT);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.view(SESSION, STREAM, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
    }

    @Test
    void nonActiveSessionIs404() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.CONSENT_GRANTED);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> controller.view(SESSION, STREAM, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
        verify(registry, never()).reserve(any(), any(), any(), any(), any());
    }

    @Test
    void noLiveAuthorizedStreamIs404() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        // owned + ACTIVE, but NO live VIEW_ONLY stream authorization for the (session, stream, peer) → opaque 404
        when(streamAuth.isAuthorized(eq(SESSION), eq(STREAM), eq(PEER), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> controller.view(SESSION, STREAM, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
        verify(registry, never()).reserve(any(), any(), any(), any(), any());
    }

    @Test
    void blankStreamIdIs404NotAParamError() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream(); // would otherwise pass — proves the blank-streamId guard short-circuits to 404
        assertThatThrownBy(() -> controller.view(SESSION, "  ", request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
        verify(streamAuth, never()).isAuthorized(any(), any(), any(), anyLong());
        verify(registry, never()).reserve(any(), any(), any(), any(), any());
    }

    @Test
    void unsafeOrOversizedStreamIdIsOpaque404BeforeAuditOrRegistry() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));

        for (String invalid : new String[] {"stream/with/slash", "x".repeat(129), "stream\npoison"}) {
            assertThatThrownBy(() -> controller.view(SESSION, invalid, request))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting("statusCode").hasToString("404 NOT_FOUND");
        }
        verify(registry, never()).reserve(any(), any(), any(), any(), any());
        verify(viewerAudit, never()).recordViewStart(any(), any(), any(), any(), any());
    }

    @Test
    void oneToOneViewerBoundIs409() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream();
        when(registry.reserve(eq(SESSION), eq(STREAM), eq(TENANT), eq(SUBJECT), any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.view(SESSION, STREAM, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("409 CONFLICT");
    }

    @Test
    void ownedActiveAuthorizedReturnsSseEmitter() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream();
        ViewOnlyViewerSubscription sub = mock(ViewOnlyViewerSubscription.class);
        when(sub.isClosed()).thenReturn(true); // emit loop exits immediately (no real SSE connection in a unit)
        when(registry.reserve(eq(SESSION), eq(STREAM), eq(TENANT), eq(SUBJECT), any()))
                .thenReturn(Optional.of(sub));
        when(registry.activate(sub)).thenReturn(true);
        SseEmitter emitter = controller.view(SESSION, STREAM, request);
        assertThat(emitter).isNotNull();
        verify(registry).reserve(eq(SESSION), eq(STREAM), eq(TENANT), eq(SUBJECT), any());
    }

    @Test
    void viewStartRecordsHashChainAuditForTheAdmittedOperator() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream();
        ViewOnlyViewerSubscription sub = mock(ViewOnlyViewerSubscription.class);
        when(sub.isClosed()).thenReturn(true);
        when(registry.reserve(eq(SESSION), eq(STREAM), eq(TENANT), eq(SUBJECT), any()))
                .thenReturn(Optional.of(sub));
        when(registry.activate(sub)).thenReturn(true);
        SseEmitter emitter = controller.view(SESSION, STREAM, request);
        assertThat(emitter).isNotNull();
        // The fail-closed VIEW_START audit is recorded (parsed UUID tenant + subject + session/device/stream)
        // synchronously, before the emit loop is scheduled.
        verify(viewerAudit).recordViewStart(
                eq(UUID.fromString(TENANT)), eq(SUBJECT), eq(SESSION), eq("device-1"), eq(STREAM));
        InOrder order = inOrder(viewerAudit, registry);
        order.verify(viewerAudit).recordViewStart(
                eq(UUID.fromString(TENANT)), eq(SUBJECT), eq(SESSION), eq("device-1"), eq(STREAM));
        order.verify(registry).activate(sub);
    }

    @Test
    void framesAreRejectedWhileViewStartAuditIsUncommitted() throws Exception {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream();
        ViewOnlyViewerRegistry realRegistry = new ViewOnlyViewerRegistry(1);
        RemoteBridgeViewerController guardedController = new RemoteBridgeViewerController(
                authenticator, sessionStore, realRegistry, streamAuth, viewerAudit, new SimpleMeterRegistry());
        CountDownLatch auditEntered = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        doAnswer(invocation -> {
            auditEntered.countDown();
            assertTrue(releaseAudit.await(5, TimeUnit.SECONDS));
            return null;
        }).when(viewerAudit).recordViewStart(any(), any(), any(), any(), any());
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<SseEmitter> view = caller.submit(() -> guardedController.view(SESSION, STREAM, request));
            assertTrue(auditEntered.await(5, TimeUnit.SECONDS));

            ViewOnlyFrame beforeCommit = new ViewOnlyFrame(
                    SESSION, STREAM, 1L, "image/png", ByteString.copyFromUtf8("frame"), false, 1L);
            assertEquals(0, realRegistry.publish(beforeCommit));

            releaseAudit.countDown();
            assertThat(view.get(5, TimeUnit.SECONDS)).isNotNull();
            realRegistry.closeSession(SESSION);
        } finally {
            releaseAudit.countDown();
            realRegistry.closeSession(SESSION);
            caller.shutdownNow();
        }
    }

    @Test
    void startAuditFailureIsFailClosed503AndReleasesTheSlot() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream();
        ViewOnlyViewerSubscription sub = mock(ViewOnlyViewerSubscription.class);
        when(registry.reserve(eq(SESSION), eq(STREAM), eq(TENANT), eq(SUBJECT), any()))
                .thenReturn(Optional.of(sub));
        // The pilot-enable HARD GATE: a hash-chain audit-write failure MUST prevent any observation.
        doThrow(new RuntimeException("audit chain unavailable"))
                .when(viewerAudit).recordViewStart(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> controller.view(SESSION, STREAM, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("503 SERVICE_UNAVAILABLE");
        verify(registry).unsubscribe(sub); // the 1:1 slot is released — no stream
        verify(viewerAudit, never()).recordViewStop(any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void reservationClosedBeforeActivationFailsClosedAndClosesAuditChain() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream();
        ViewOnlyViewerSubscription sub = mock(ViewOnlyViewerSubscription.class);
        when(registry.reserve(eq(SESSION), eq(STREAM), eq(TENANT), eq(SUBJECT), any()))
                .thenReturn(Optional.of(sub));
        when(registry.activate(sub)).thenReturn(false);

        assertThatThrownBy(() -> controller.view(SESSION, STREAM, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("503 SERVICE_UNAVAILABLE");

        verify(registry).unsubscribe(sub);
        verify(viewerAudit).recordViewStop(
                eq(UUID.fromString(TENANT)), eq(SUBJECT), eq(SESSION), eq("device-1"), eq(STREAM), eq(0L), eq(0L));
    }

    @Test
    void browserRenderAcknowledgementIsBoundToOwnedLiveViewer() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream();
        when(registry.acknowledgeRendered(eq(SESSION), eq(STREAM), eq(TENANT), eq(SUBJECT),
                eq("vw-random"), eq(7L), anyLong()))
                .thenReturn(Optional.of(new RenderAcknowledgement(7L, 100L, 120L, 180L, 80L, true)));

        controller.acknowledgeRendered(SESSION, STREAM,
                new RemoteBridgeViewerController.RenderAckRequest("vw-random", 7L), request);

        verify(registry).acknowledgeRendered(eq(SESSION), eq(STREAM), eq(TENANT), eq(SUBJECT),
                eq("vw-random"), eq(7L), anyLong());
    }

    @Test
    void unknownOrReplayedRenderAcknowledgementIsOpaque404() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream();
        when(registry.acknowledgeRendered(eq(SESSION), eq(STREAM), eq(TENANT), eq(SUBJECT),
                eq("vw-stale"), eq(7L), anyLong()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.acknowledgeRendered(SESSION, STREAM,
                new RemoteBridgeViewerController.RenderAckRequest("vw-stale", 7L), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
    }

    @Test
    void renderAcknowledgementAfterSessionTerminationIs404BeforeRegistry() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.CLOSED);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> controller.acknowledgeRendered(SESSION, STREAM,
                new RemoteBridgeViewerController.RenderAckRequest("vw-random", 7L), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
        verify(registry, never()).acknowledgeRendered(any(), any(), any(), any(), any(), anyLong(), anyLong());
    }
}
