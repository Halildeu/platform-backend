package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteSessionApprovalRecorder;
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

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Faz 22.6 D10 approval write-path (Codex 019ebe06; PR-2) — the approver's REST endpoint: a SECOND operator
 * records a dual-control approval, which fills the {@link com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalGrantStore}
 * so the broker can PERMIT. The {@link RemoteSessionApprovalRecorder} runs the E10 flow + records on ALLOWED.
 *
 * <p><b>Security invariants (Codex; mirrors the operator controller):</b>
 * <ul>
 *   <li><b>Authenticate first</b> — the approver's identity comes from their OWN verified JWT
 *       ({@link OperatorCredentialExtractor} → {@link OperatorAuthenticator}), NEVER a request body; a
 *       non-authenticated identity ⇒ 401.</li>
 *   <li><b>Capabilities parsed BEFORE the session lookup</b> — a malformed / non-pilot capability set ⇒ 400 and
 *       the session store is never touched.</li>
 *   <li><b>Tenant-filtered lookup</b> — the session must exist AND be in the approver's tenant; missing OR
 *       cross-tenant ⇒ the SAME 404 (no existence oracle). NOT subject-filtered: the approver is a DIFFERENT
 *       subject from the session's operator (that is the whole point of dual-control — the recorder enforces
 *       approver≠requester via the E10 gate).</li>
 *   <li><b>No verdict oracle</b> — a missing session, a cross-tenant session, and EVERY recorder denial
 *       ({@code DENIED_TENANT_MISMATCH / _INVALID_CAPABILITIES / _APPROVAL}) collapse to the SAME 404; only a
 *       recorded approval is 200. The distinct reason stays in the recorder's audit detail.</li>
 * </ul>
 *
 * <p>Gated by {@code remote-bridge.approval-rest.enabled} (its own authority surface — see RemoteBridgeApprovalConfig).
 */
@RestController
@RequestMapping("/internal/remote-bridge/approval")
@ConditionalOnProperty(prefix = "remote-bridge.approval-rest", name = "enabled", havingValue = "true")
public class RemoteBridgeApprovalController {

    private final RemoteSessionApprovalRecorder recorder;
    private final OperatorAuthenticator authenticator;
    private final RemoteBridgeSessionStore sessionStore;
    private final LongSupplier clock;

    @Autowired
    public RemoteBridgeApprovalController(RemoteSessionApprovalRecorder remoteBridgeApprovalRecorder,
                                          OperatorAuthenticator remoteBridgeOperatorAuthenticator,
                                          RemoteBridgeSessionStore remoteBridgeSessionStore) {
        this(remoteBridgeApprovalRecorder, remoteBridgeOperatorAuthenticator, remoteBridgeSessionStore,
                System::currentTimeMillis);
    }

    /** Package-private seam so a test can pin the clock. */
    RemoteBridgeApprovalController(RemoteSessionApprovalRecorder recorder, OperatorAuthenticator authenticator,
                                  RemoteBridgeSessionStore sessionStore, LongSupplier clock) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/sessions/{sessionId}/approve")
    public ResponseEntity<?> approve(@PathVariable String sessionId,
                                     @RequestBody(required = false) ApprovalRequestBody body,
                                     HttpServletRequest request) {
        OperatorIdentity approver = authenticate(request);
        if (!approver.isAuthenticated()) {
            return unauthenticated(); // 401 — no verified approver, no orchestration
        }

        Set<RemoteSessionCapability> approvedCapabilities;
        try {
            approvedCapabilities = parseCapabilities(body == null ? null : body.capabilities());
        } catch (IllegalArgumentException malformed) {
            return ResponseEntity.badRequest().build(); // 400 — before any session lookup
        }

        // tenant-filtered (NOT subject-filtered): the approver is a distinct subject from the session's operator
        Optional<RemoteBridgeSession> session = tenantSession(sessionId, approver);
        if (session.isEmpty()) {
            return notFoundOrRefused(); // 404 — missing OR cross-tenant, uniform (no existence oracle)
        }

        RemoteSessionApprovalRecorder.Result result = recorder.record(session.get(),
                approver.operatorSubject(), approver.tenantId(), approvedCapabilities, clock.getAsLong());
        if (result != RemoteSessionApprovalRecorder.Result.RECORDED) {
            return notFoundOrRefused(); // 404 — EVERY denial collapses to the same response (no verdict oracle)
        }
        return ResponseEntity.ok(new ApprovalResponse("recorded"));
    }

    private OperatorIdentity authenticate(HttpServletRequest request) {
        return authenticator.authenticate(OperatorCredentialExtractor.extract(request));
    }

    /** The session iff it exists AND is in the approver's tenant (canonical UUID); else empty (⇒ uniform 404). */
    private Optional<RemoteBridgeSession> tenantSession(String sessionId, OperatorIdentity approver) {
        UUID tenantId = parseUuidOrNull(approver.tenantId());
        if (tenantId == null) {
            return Optional.empty();
        }
        String canonicalTenant = tenantId.toString();
        return sessionStore.bySessionId(sessionId)
                .filter(session -> canonicalTenant.equals(session.operatorTenantId()));
    }

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static ResponseEntity<?> notFoundOrRefused() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new RefusedResponse("not-found-or-refused"));
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

    /** Non-empty, all-pilot enum set; ANY problem throws ⇒ the caller maps it to 400 before the session lookup. */
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

    // ---- wire DTOs (the approver subject/tenant are NEVER taken from the body — only the verified JWT) ----

    public record ApprovalRequestBody(List<String> capabilities) {
    }

    public record ApprovalResponse(String status) {
    }

    public record RefusedResponse(String status) {
    }
}
