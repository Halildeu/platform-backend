package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpAssertion;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ConnectedDeviceResolver;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OperatorStepUpHandler;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.OperatorOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.SessionOpenOutcome;
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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private final ConnectedDeviceResolver deviceResolver;
    private final LongSupplier clock;

    @Autowired
    public RemoteBridgeOperatorController(RemoteBridgeOperatorService operatorService,
                                          OperatorStepUpHandler stepUpHandler,
                                          OperatorAuthenticator authenticator,
                                          RemoteBridgeSessionStore sessionStore,
                                          ConnectedDeviceResolver deviceResolver) {
        this(operatorService, stepUpHandler, authenticator, sessionStore, deviceResolver, System::currentTimeMillis);
    }

    /** Package-private seam so a test can pin the clock. */
    RemoteBridgeOperatorController(RemoteBridgeOperatorService operatorService,
                                   OperatorStepUpHandler stepUpHandler,
                                   OperatorAuthenticator authenticator,
                                   RemoteBridgeSessionStore sessionStore,
                                   ConnectedDeviceResolver deviceResolver,
                                   LongSupplier clock) {
        this.operatorService = Objects.requireNonNull(operatorService, "operatorService");
        this.stepUpHandler = Objects.requireNonNull(stepUpHandler, "stepUpHandler");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.deviceResolver = Objects.requireNonNull(deviceResolver, "deviceResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Open an ATTENDED session to a device the operator's OWN tenant owns: resolve the verified connected peer
     * ({@link ConnectedDeviceResolver}), then drive the broker's consent flow. The operator subject AND tenant
     * come from the AUTHENTICATED identity, never the body — the body only names the target device + reason +
     * capabilities. This is the path-LESS create (no {@code {sessionId}}); the operator-supplied sessionId is
     * the new session's id — a duplicate is REFUSED (not idempotent-success), and it cannot target another
     * operator's session.
     *
     * <p><b>Full request-shape validation BEFORE the resolver (Codex REVISE):</b> every malformed input (a bad
     * subject, a non-canonical/invalid id, a missing/non-pilot/null capability) is rejected before
     * {@code resolveConnectedPeer} is ever called, so a malformed request can never probe whether a device is
     * connected (the response never depends on device state until the request is well-formed). The deviceId is
     * canonicalized ({@code UUID.toString()}) into the {@link SessionRequest} so a whitespace-padded raw id
     * cannot reach the store and be rejected LATE (another oracle).
     */
    @PostMapping("/sessions")
    public ResponseEntity<?> openSession(@RequestBody(required = false) OpenSessionRequestBody body,
                                         HttpServletRequest request) {
        OperatorIdentity identity = authenticate(request);
        if (!identity.isAuthenticated()) {
            return unauthenticated();
        }
        // the tenant is the operator's VERIFIED tenant — a non-UUID tenant is an unusable identity; refuse
        // before any resolver/service call (Codex: tenant parse failure must not reach the data plane)
        UUID tenantId = parseUuidOrNull(identity.tenantId());
        if (tenantId == null) {
            return unauthenticated();
        }
        // a verified subject that is not a valid wire id is an auth/IdP misconfig — fail-closed before the data plane
        if (!WireContract.isValidId(identity.operatorSubject())) {
            return unauthenticated();
        }
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID deviceId = parseUuidOrNull(body.deviceId());
        if (deviceId == null) {
            return ResponseEntity.badRequest().build(); // a missing/blank/malformed client deviceId is a bad request
        }
        Set<RemoteSessionCapability> capabilities;
        try {
            capabilities = parseCapabilities(body.capabilities());
        } catch (IllegalArgumentException invalidCapabilities) {
            return ResponseEntity.badRequest().build(); // missing/null/unknown/non-pilot capability — no resolver call
        }
        // build with the AUTHENTICATED subject + the CANONICAL device id (never the raw body id), then validate
        // the WHOLE request-shape before the resolver so a malformed request can never probe device state
        SessionRequest sessionRequest = new SessionRequest(body.sessionId(), deviceId.toString(),
                identity.operatorSubject(), body.reason(), capabilities);
        if (!WireContract.isValid(sessionRequest)) {
            return ResponseEntity.badRequest().build(); // invalid sessionId / shape — fail-closed before the resolver
        }
        PeerIdentity peer = deviceResolver
                .resolveConnectedPeer(tenantId, deviceId, Instant.ofEpochMilli(clock.getAsLong()))
                .orElse(null);
        if (peer == null) {
            return notFound(); // not connected / not enrolled / cross-tenant — same 404, no existence oracle
        }
        // the session is pinned to the AUTHENTICATED tenant (canonical) so the follow-up ownership guard is
        // tenant-scoped, not just subject-scoped (Codex REVISE)
        SessionOpenOutcome outcome = operatorService.openSession(sessionRequest, peer, tenantId.toString(),
                identity.operatorSubject());
        if (!outcome.opened()) {
            // a GENERIC refusal — the internal reason (e.g. a duplicate session id) is audited upstream, never
            // echoed, so a guessed sessionId cannot become a collision oracle (Codex REVISE)
            return ResponseEntity.unprocessableEntity().body(new RejectedResponse("open-session-refused"));
        }
        return ResponseEntity.ok(new OpenSessionResponse(outcome.sessionId(), outcome.consentPromptSent()));
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
        return ResponseEntity.ok(new OperationResponse(outcome.brokerOutcome().kind().name(),
                outcome.transportPushed(), PermitMetadata.from(outcome.brokerOutcome().permit(), clock.getAsLong())));
    }

    private OperatorIdentity authenticate(HttpServletRequest request) {
        return authenticator.authenticate(OperatorCredentialExtractor.extract(request));
    }

    /**
     * The session iff it exists AND is owned by the authenticated operator — gated on BOTH the operator's
     * tenant AND subject (Codex REVISE: subject alone is not a tenancy boundary, so an operator with the same
     * subject in a DIFFERENT tenant must not pass). A non-UUID identity tenant owns nothing (fail-closed).
     * Otherwise empty (⇒ 404, same response for missing/not-owned — no existence oracle).
     */
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

    /** Parse a canonical UUID or null — a non-UUID value never reaches the data plane (fail-closed). */
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

    /**
     * Map the requested capability names to a non-empty, all-pilot enum set; ANY problem throws so the caller
     * maps it to 400 BEFORE the resolver (Codex REVISE — a non-pilot capability would otherwise reach the store
     * and reject LATE, leaking device-connected state). Rejects: missing/empty, a null element, an unknown name,
     * and a capability outside {@link RemoteSessionCapability#PILOT_ALLOWED}.
     */
    private static Set<RemoteSessionCapability> parseCapabilities(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("at least one capability is required");
        }
        EnumSet<RemoteSessionCapability> capabilities = EnumSet.noneOf(RemoteSessionCapability.class);
        for (String name : requested) {
            if (name == null) {
                throw new IllegalArgumentException("a null capability is not allowed");
            }
            RemoteSessionCapability capability = RemoteSessionCapability.valueOf(name); // unknown → IllegalArgumentException
            if (!RemoteSessionCapability.PILOT_ALLOWED.contains(capability)) {
                throw new IllegalArgumentException("non-pilot capability: " + name);
            }
            capabilities.add(capability);
        }
        return capabilities;
    }

    // ---- wire DTOs (server-shaped; the operator subject is NEVER taken from a request body) ----

    public record OpenSessionRequestBody(String sessionId, String deviceId, String reason, List<String> capabilities) {
    }

    public record OpenSessionResponse(String sessionId, boolean consentPromptSent) {
    }

    public record ChallengeResponse(String challengeB64, String expectedOrigin, long issuedAtEpochMillis) {
    }

    public record VerifyRequest(String clientDataJsonB64, String authenticatorDataB64, String signatureB64) {
    }

    public record VerifyResponse(boolean verified, String achievedStrength) {
    }

    public record OperationRequestBody(String operationId, String operation, String commandLine) {
    }

    public record OperationResponse(String kind, boolean transportPushed, PermitMetadata permit) {
    }

    public record PermitMetadata(String alg,
                                 String kid,
                                 int permitVersion,
                                 String policyVersion,
                                 String decisionId,
                                 String sessionId,
                                 String operationId,
                                 String deviceIdSha256,
                                 String operatorSubjectSha256,
                                 String capability,
                                 String commandHash,
                                 long issuedAtEpochMillis,
                                 long expiresAtEpochMillis,
                                 long seq,
                                 boolean signaturePresent,
                                 String canonicalPayloadSha256,
                                 boolean freshAtResponseTime) {
        static PermitMetadata from(OperationPermit permit, long nowEpochMillis) {
            if (permit == null) {
                return null;
            }
            return new PermitMetadata(
                    permit.alg(),
                    permit.kid(),
                    permit.permitVersion(),
                    permit.policyVersion(),
                    permit.decisionId(),
                    permit.sessionId(),
                    permit.operationId(),
                    sha256Hex(permit.deviceId()),
                    sha256Hex(permit.operatorSubject()),
                    permit.capability() == null ? null : permit.capability().name(),
                    permit.commandHash(),
                    permit.issuedAtEpochMillis(),
                    permit.expiresAtEpochMillis(),
                    permit.seq(),
                    permit.signatureB64() != null && !permit.signatureB64().isBlank(),
                    sha256Hex(permit.canonicalPayload()),
                    permit.isFresh(nowEpochMillis));
        }
    }

    public record RejectedResponse(String reason) {
    }

    private static String sha256Hex(String value) {
        return sha256Hex(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
