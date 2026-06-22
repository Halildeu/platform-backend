package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeNegativeProbeService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeNegativeProbeService.ProbeOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Non-prod, opt-in operator endpoints that produce product-supported acceptance evidence for agent-side
 * negative permit handling. These endpoints are not a support capability and are forbidden in prod-like
 * profiles; they only push bounded {@code hostname} probes and succeed only when the real agent reports a deny.
 */
@RestController
@RequestMapping("/internal/remote-bridge/operator")
@ConditionalOnExpression("'${remote-bridge.operator-rest.enabled:false}' == 'true' "
        + "&& '${remote-bridge.negative-probes.enabled:false}' == 'true'")
public class RemoteBridgeNegativeProbeController {

    private final RemoteBridgeNegativeProbeService probeService;
    private final OperatorAuthenticator authenticator;
    private final RemoteBridgeSessionStore sessionStore;

    @Autowired
    public RemoteBridgeNegativeProbeController(RemoteBridgeNegativeProbeService probeService,
                                               OperatorAuthenticator authenticator,
                                               RemoteBridgeSessionStore sessionStore,
                                               Environment environment) {
        this(probeService, authenticator, sessionStore, isProductionLike(environment));
    }

    RemoteBridgeNegativeProbeController(RemoteBridgeNegativeProbeService probeService,
                                        OperatorAuthenticator authenticator,
                                        RemoteBridgeSessionStore sessionStore,
                                        boolean productionLike) {
        if (productionLike) {
            throw new IllegalStateException("remote-bridge negative probes are non-prod acceptance-only");
        }
        this.probeService = Objects.requireNonNull(probeService, "probeService");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    }

    @PostMapping("/sessions/{sessionId}/negative-probes/expired-permit")
    public ResponseEntity<?> expiredPermit(@PathVariable String sessionId, HttpServletRequest request) {
        OperatorIdentity identity = authenticate(request);
        if (!identity.isAuthenticated()) {
            return unauthenticated();
        }
        Optional<RemoteBridgeSession> session = ownedSession(sessionId, identity);
        if (session.isEmpty()) {
            return notFound();
        }
        return map(probeService.expiredPermit(session.get()));
    }

    @PostMapping("/sessions/{sessionId}/negative-probes/replay")
    public ResponseEntity<?> replay(@PathVariable String sessionId, HttpServletRequest request) {
        OperatorIdentity identity = authenticate(request);
        if (!identity.isAuthenticated()) {
            return unauthenticated();
        }
        Optional<RemoteBridgeSession> session = ownedSession(sessionId, identity);
        if (session.isEmpty()) {
            return notFound();
        }
        return map(probeService.replayPermit(session.get()));
    }

    private ResponseEntity<?> map(ProbeOutcome outcome) {
        ProbeResponse body = ProbeResponse.from(outcome);
        if (outcome.observedDeny()) {
            return ResponseEntity.unprocessableEntity().body(body);
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private OperatorIdentity authenticate(HttpServletRequest request) {
        return authenticator.authenticate(OperatorCredentialExtractor.extract(request));
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

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static boolean isProductionLike(Environment environment) {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            String normalized = profile == null ? "" : profile.strip().toLowerCase(Locale.ROOT);
            if ("prod".equals(normalized) || "production".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public record ProbeResponse(String kind,
                                String reason,
                                boolean transportPushed,
                                String agentErrorCode,
                                String operationId,
                                long seq) {
        static ProbeResponse from(ProbeOutcome outcome) {
            return new ProbeResponse(outcome.observedDeny() ? "DENY" : "REFUSED", outcome.reason(),
                    outcome.transportPushed(), outcome.agentErrorCode(), outcome.operationId(), outcome.seq());
        }
    }
}
