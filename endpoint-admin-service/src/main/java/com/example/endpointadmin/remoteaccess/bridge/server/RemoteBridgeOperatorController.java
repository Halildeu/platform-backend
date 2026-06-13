package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpAssertion;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OperatorStepUpHandler;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.OperatorOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Faz 22.6 slice-4c-2b-1 (Codex 019ebe06) — the operator-facing REST transport for operations on an ALREADY
 * OPEN attended session: issue/verify a WebAuthn step-up, and submit an operation through the broker. The
 * counterpart to the agent-facing gRPC transport; it turns an operator HTTP call into a call on the
 * orchestration beans (slice-4b) after authenticating the operator (slice-4c-2a) and proving session
 * ownership.
 *
 * <p><b>Disabled-by-default.</b> Gated by {@code remote-bridge.operator-rest.enabled=true}; with the property
 * absent or false the controller bean is never created, so the endpoint does not exist (fail-closed). It
 * depends on the broker orchestration beans, which exist only behind the global {@code remote-bridge
 * .enabled=true}; turning on the REST gate WITHOUT the global gate is a misconfiguration that fails the
 * context closed at startup (a loud error, never a silently half-wired endpoint).
 *
 * <p><b>Security invariants (fail-closed):</b>
 * <ul>
 *   <li><b>Authenticate first.</b> Every method authenticates the operator credential
 *       ({@link OperatorCredentialExtractor} → {@link OperatorAuthenticator}). A non-authenticated identity ⇒
 *       {@code 401} and NO orchestration bean is touched.</li>
 *   <li><b>Ownership, not existence.</b> An operator may act only on a session whose stored
 *       {@code operatorSubject} equals the AUTHENTICATED subject (never a client-supplied field). A missing OR
 *       not-owned session both yield {@code 404} — same response, so a probing operator cannot tell another
 *       operator's session id from a non-existent one (no existence oracle).</li>
 *   <li><b>No verdict oracle.</b> A step-up verify returns only {@code verified} + the achieved strength,
 *       never the internal {@link com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.Verdict} (why
 *       it failed).</li>
 *   <li><b>Path is authoritative.</b> The {@code sessionId} comes from the URL path, not the body, so a body
 *       cannot target a different session than the one the ownership check validated.</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/remote-bridge/operator")
@ConditionalOnProperty(prefix = "remote-bridge.operator-rest", name = "enabled", havingValue = "true")
public class RemoteBridgeOperatorController {

    private final RemoteBridgeOperatorService operatorService;
    private final OperatorStepUpHandler stepUpHandler;
    private final OperatorAuthenticator authenticator;
    private final RemoteBridgeSessionStore sessionStore;
    private final LongSupplier clock;

    @Autowired
    public RemoteBridgeOperatorController(RemoteBridgeOperatorService operatorService,
                                          OperatorStepUpHandler stepUpHandler,
                                          OperatorAuthenticator authenticator,
                                          RemoteBridgeSessionStore sessionStore) {
        this(operatorService, stepUpHandler, authenticator, sessionStore, System::currentTimeMillis);
    }

    /** Package-private seam so a test can pin the clock. */
    RemoteBridgeOperatorController(RemoteBridgeOperatorService operatorService,
                                   OperatorStepUpHandler stepUpHandler,
                                   OperatorAuthenticator authenticator,
                                   RemoteBridgeSessionStore sessionStore,
                                   LongSupplier clock) {
        this.operatorService = Objects.requireNonNull(operatorService, "operatorService");
        this.stepUpHandler = Objects.requireNonNull(stepUpHandler, "stepUpHandler");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Issue a fresh step-up challenge for the operator's own session (replaces any prior pending one). */
    @PostMapping("/sessions/{sessionId}/step-up/challenge")
    public ResponseEntity<?> issueStepUpChallenge(@PathVariable String sessionId, HttpServletRequest request) {
        OperatorIdentity identity = authenticate(request);
        if (!identity.isAuthenticated()) {
            return unauthenticated();
        }
        if (ownedSession(sessionId, identity).isEmpty()) {
            return notFound();
        }
        Optional<StepUpChallenge> challenge = stepUpHandler.issueChallenge(sessionId, clock.getAsLong());
        // owned-but-unissuable (state/race) ⇒ 409, not a leak of why
        return challenge
                .<ResponseEntity<?>>map(c -> ResponseEntity.ok(new ChallengeResponse(
                        c.challengeB64(), c.expectedOrigin(), c.issuedAtEpochMillis())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    /** Verify the operator's WebAuthn assertion against the session's pending challenge and record the step-up. */
    @PostMapping("/sessions/{sessionId}/step-up/verify")
    public ResponseEntity<?> verifyStepUp(@PathVariable String sessionId,
                                          @RequestBody(required = false) VerifyRequest body,
                                          HttpServletRequest request) {
        OperatorIdentity identity = authenticate(request);
        if (!identity.isAuthenticated()) {
            return unauthenticated();
        }
        if (ownedSession(sessionId, identity).isEmpty()) {
            return notFound();
        }
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        StepUpAssertion assertion = new StepUpAssertion(
                body.clientDataJsonB64(), body.authenticatorDataB64(), body.signatureB64());
        StepUpVerification verification = stepUpHandler.verifyAndRecord(sessionId, assertion, clock.getAsLong());
        // verified yes/no + achieved strength only — the internal Verdict (why it failed) is never an oracle
        return ResponseEntity.ok(new VerifyResponse(
                verification.isVerified(), verification.achievedStrength().name()));
    }

    /** Submit an operation on the operator's own session; the broker is the sole authority on the verdict. */
    @PostMapping("/sessions/{sessionId}/operations")
    public ResponseEntity<?> submitOperation(@PathVariable String sessionId,
                                             @RequestBody(required = false) OperationRequestBody body,
                                             HttpServletRequest request) {
        OperatorIdentity identity = authenticate(request);
        if (!identity.isAuthenticated()) {
            return unauthenticated();
        }
        if (ownedSession(sessionId, identity).isEmpty()) {
            return notFound();
        }
        if (body == null || body.operationId() == null || body.operationId().isBlank() || body.operation() == null) {
            return ResponseEntity.badRequest().build();
        }
        RemoteOperation operation;
        try {
            operation = RemoteOperation.valueOf(body.operation());
        } catch (IllegalArgumentException unknownOperation) {
            return ResponseEntity.badRequest().build(); // an unknown operation name is a malformed request, not a leak
        }
        // sessionId is taken from the PATH (the ownership-validated one), never from the body
        OperationRequest opRequest =
                new OperationRequest(sessionId, body.operationId(), operation, body.commandLine());
        OperatorOutcome outcome = operatorService.handleOperationRequest(opRequest);
        if (!outcome.accepted()) {
            return ResponseEntity.unprocessableEntity().body(new RejectedResponse(outcome.rejectReason()));
        }
        return ResponseEntity.ok(new OperationResponse(outcome.brokerOutcome().kind().name(), outcome.transportPushed()));
    }

    private OperatorIdentity authenticate(HttpServletRequest request) {
        return authenticator.authenticate(OperatorCredentialExtractor.extract(request));
    }

    /** The session iff it exists AND is owned by the authenticated operator; otherwise empty (⇒ 404). */
    private Optional<RemoteBridgeSession> ownedSession(String sessionId, OperatorIdentity identity) {
        return sessionStore.bySessionId(sessionId)
                .filter(session -> Objects.equals(session.operatorSubject(), identity.operatorSubject()));
    }

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    // ---- wire DTOs (server-shaped; the operator subject is NEVER taken from a request body) ----

    public record ChallengeResponse(String challengeB64, String expectedOrigin, long issuedAtEpochMillis) {
    }

    public record VerifyRequest(String clientDataJsonB64, String authenticatorDataB64, String signatureB64) {
    }

    public record VerifyResponse(boolean verified, String achievedStrength) {
    }

    public record OperationRequestBody(String operationId, String operation, String commandLine) {
    }

    public record OperationResponse(String kind, boolean transportPushed) {
    }

    public record RejectedResponse(String reason) {
    }
}
