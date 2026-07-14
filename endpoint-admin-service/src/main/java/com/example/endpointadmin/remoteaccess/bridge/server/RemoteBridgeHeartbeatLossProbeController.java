package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeHeartbeatLossProbeService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeHeartbeatLossProbeService.ProbeOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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

/** Authenticated, owner-bound and production-forbidden surface for the real heartbeat watchdog acceptance run. */
@RestController
@RequestMapping("/internal/remote-bridge/operator")
@ConditionalOnExpression("'${remote-bridge.operator-rest.enabled:false}' == 'true' "
        + "&& '${remote-bridge.heartbeat-loss-probes.enabled:false}' == 'true'")
public class RemoteBridgeHeartbeatLossProbeController {

    private final RemoteBridgeHeartbeatLossProbeService probeService;
    private final OperatorAuthenticator authenticator;
    private final RemoteBridgeSessionStore sessionStore;

    @Autowired
    public RemoteBridgeHeartbeatLossProbeController(RemoteBridgeHeartbeatLossProbeService probeService,
                                                    OperatorAuthenticator authenticator,
                                                    RemoteBridgeSessionStore sessionStore,
                                                    Environment environment) {
        this(probeService, authenticator, sessionStore, isProductionLike(environment));
    }

    RemoteBridgeHeartbeatLossProbeController(RemoteBridgeHeartbeatLossProbeService probeService,
                                             OperatorAuthenticator authenticator,
                                             RemoteBridgeSessionStore sessionStore,
                                             boolean productionLike) {
        if (productionLike) {
            throw new IllegalStateException("remote-bridge heartbeat-loss probes are non-prod acceptance-only");
        }
        this.probeService = Objects.requireNonNull(probeService, "probeService");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    }

    @PostMapping("/sessions/{sessionId}/termination-probes/heartbeat-loss")
    public ResponseEntity<?> heartbeatLoss(@PathVariable String sessionId, HttpServletRequest request) {
        OperatorIdentity identity = authenticator.authenticate(OperatorCredentialExtractor.extract(request));
        if (!identity.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<RemoteBridgeSession> session = ownedSession(sessionId, identity);
        if (session.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        ProbeOutcome outcome = probeService.exercise(session.orElseThrow());
        ProbeResponse body = ProbeResponse.from(outcome);
        return outcome.terminalObserved()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(body);
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
                                String probeId,
                                long suppressedUntilEpochMillis,
                                String terminalState) {
        static ProbeResponse from(ProbeOutcome outcome) {
            return new ProbeResponse(outcome.terminalObserved() ? "TERMINATED" : "REFUSED", outcome.reason(),
                    outcome.probeId(), outcome.suppressedUntilEpochMillis(), outcome.terminalState());
        }
    }
}
