package com.example.endpointadmin.remoteaccess.bridge.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
 * (tenant+subject) with a no-existence-oracle 404, ACTIVE-only, and the 1:1 viewer bound. (SSE streaming itself
 * is browser-verified.)
 */
class RemoteBridgeViewerControllerTest {

    private static final String SESSION = "sess-1";
    private static final String TENANT = UUID.randomUUID().toString();
    private static final String SUBJECT = "operator@example.com";

    private final OperatorAuthenticator authenticator = mock(OperatorAuthenticator.class);
    private final RemoteBridgeSessionStore sessionStore = mock(RemoteBridgeSessionStore.class);
    private final ViewOnlyViewerRegistry registry = mock(ViewOnlyViewerRegistry.class);
    private final RemoteBridgeViewerController controller =
            new RemoteBridgeViewerController(authenticator, sessionStore, registry, new SimpleMeterRegistry());
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
        return s;
    }

    @Test
    void unauthenticatedIsRejected401() {
        when(authenticator.authenticate(any())).thenReturn(OperatorIdentity.unauthenticated());
        assertThatThrownBy(() -> controller.view(SESSION, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("401 UNAUTHORIZED");
        verify(registry, never()).subscribe(any(), any());
    }

    @Test
    void notOwnedSessionIs404NoOracle() {
        authedAs(TENANT, SUBJECT);
        // session owned by a DIFFERENT tenant → the ownedSession filter fails → opaque 404
        RemoteBridgeSession other = session(UUID.randomUUID().toString(), SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> controller.view(SESSION, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
        verify(registry, never()).subscribe(any(), any());
    }

    @Test
    void missingSessionIs404() {
        authedAs(TENANT, SUBJECT);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.view(SESSION, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
    }

    @Test
    void nonActiveSessionIs404() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.CONSENT_GRANTED);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> controller.view(SESSION, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("404 NOT_FOUND");
        verify(registry, never()).subscribe(any(), any());
    }

    @Test
    void oneToOneViewerBoundIs409() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        when(registry.subscribe(eq(SESSION), any())).thenReturn(Optional.empty()); // bound already taken
        assertThatThrownBy(() -> controller.view(SESSION, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode").hasToString("409 CONFLICT");
    }

    @Test
    void ownedActiveSessionReturnsSseEmitter() {
        authedAs(TENANT, SUBJECT);
        RemoteBridgeSession s = session(TENANT, SUBJECT, State.ACTIVE);
        when(sessionStore.bySessionId(SESSION)).thenReturn(Optional.of(s));
        ViewOnlyViewerSubscription sub = mock(ViewOnlyViewerSubscription.class);
        when(sub.isClosed()).thenReturn(true); // emit loop exits immediately (no real SSE connection in a unit)
        when(registry.subscribe(eq(SESSION), any())).thenReturn(Optional.of(sub));
        SseEmitter emitter = controller.view(SESSION, request);
        assertThat(emitter).isNotNull();
        verify(registry).subscribe(eq(SESSION), any());
    }
}
