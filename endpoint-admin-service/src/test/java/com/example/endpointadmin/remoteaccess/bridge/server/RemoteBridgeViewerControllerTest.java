package com.example.endpointadmin.remoteaccess.bridge.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerSubscription;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
    private final RemoteBridgeViewerController controller =
            new RemoteBridgeViewerController(authenticator, sessionStore, registry, streamAuth, new SimpleMeterRegistry());
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
        verify(registry, never()).subscribe(any(), any());
    }

    @Test
    void notOwnedSessionIs404NoOracle() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession other = session(UUID.randomUUID().toString(), SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> controller.view(SESSION, STREAM, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
        verify(registry, never()).subscribe(any(), any());
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
        verify(registry, never()).subscribe(any(), any());
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
        verify(registry, never()).subscribe(any(), any());
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
        verify(registry, never()).subscribe(any(), any());
    }

    @Test
    void oneToOneViewerBoundIs409() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        authorizedStream();
        when(registry.subscribe(eq(SESSION), any())).thenReturn(Optional.empty()); // bound already taken
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
        when(registry.subscribe(eq(SESSION), any())).thenReturn(Optional.of(sub));
        SseEmitter emitter = controller.view(SESSION, STREAM, request);
        assertThat(emitter).isNotNull();
        verify(registry).subscribe(eq(SESSION), any());
    }
}
