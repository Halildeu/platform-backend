package com.example.endpointadmin.remoteaccess.bridge.wire;

import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;
import com.example.endpointadmin.remoteaccess.bridge.proto.AgentHello;
import com.example.endpointadmin.remoteaccess.bridge.proto.AuditEvent;
import com.example.endpointadmin.remoteaccess.bridge.proto.Capability;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.ConsentPrompt;
import com.example.endpointadmin.remoteaccess.bridge.proto.ConsentResult;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.Kill;
import com.example.endpointadmin.remoteaccess.bridge.proto.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.proto.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.proto.WireOperation;

import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Faz 22.6 T-2a (Codex 019eb9fb) — the ONLY boundary between the generated protobuf classes and the T-1
 * domain records. The broker / state machine / signer never see a proto class (the T-1 acceptance boundary);
 * everything that crosses this boundary is RE-validated here, fail-closed:
 *
 * <ul>
 *   <li><b>Identifiers</b> re-pass {@link WireContract#isValidId} on decode (audit/log-injection guard).</li>
 *   <li><b>Enums default-deny:</b> {@code *_UNSPECIFIED}, {@code UNRECOGNIZED} (an unknown wire number), and
 *       every non-pilot value reject the message — never mapped to a pilot value, never silently dropped.</li>
 *   <li><b>proto3 implicit defaults</b> are handled explicitly: optional text fields normalise empty → null
 *       on decode and null → empty on encode; required text fields reject blank.</li>
 *   <li><b>{@link OperationPermit}</b> decodes strictly (version pin, alg pin, capability↔commandHash
 *       consistency, positive validity window, base64-checked signature) — and its
 *       {@link OperationPermit#canonicalPayload()} is preserved byte-for-byte across encode/decode (tested),
 *       so a broker signature still verifies after the wire round-trip.</li>
 * </ul>
 *
 * <p>Naming: short class names are the PROTO side ({@code ...bridge.proto.*}); the domain side is written
 * qualified ({@code RemoteBridgeMessages.X}). The one collision-forced exception: domain
 * {@link OperationPermit} is imported short, so its proto twin appears fully-qualified.
 */
public final class RemoteBridgeProtoAdapter {

    private RemoteBridgeProtoAdapter() {
    }

    /**
     * A permit {@code decisionId} is the broker's composite {@code sessionId:operationId} (T-1b-ii), so its
     * length bound is two wire ids plus the separator — same character allowlist as {@link WireContract}.
     */
    private static final Pattern DECISION_ID = Pattern.compile("[A-Za-z0-9._:@+=-]{1,513}");

    /** {@code CanonicalCommand.hash()} — SHA-256, lowercase hex. */
    private static final Pattern COMMAND_HASH = Pattern.compile("[0-9a-f]{64}");

    /**
     * Free-text wire fields (display name, reason, kill reason, event type): bounded and free of control
     * characters (they flow into audit records and logs), but NOT restricted to the id allowlist — a human
     * display name legitimately contains spaces.
     */
    private static final int MAX_TEXT = 256;

    private static boolean isSafeText(String s, boolean required) {
        if (s == null || s.isEmpty()) {
            return !required;
        }
        return s.length() <= MAX_TEXT && s.chars().noneMatch(c -> c < 0x20 || c == 0x7F);
    }

    /** Optional proto3 string → domain: empty means absent. */
    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Domain string → proto3: null means empty. */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // Enums — default-deny in BOTH directions
    // ------------------------------------------------------------------

    /** Pilot capabilities only; {@code UNSPECIFIED}/{@code UNRECOGNIZED}/future values reject. */
    public static DecodeResult<RemoteSessionCapability> decodeCapability(Capability capability) {
        if (capability == null) {
            return DecodeResult.reject("capability-null");
        }
        return switch (capability) {
            case VIEW_ONLY -> DecodeResult.ok(RemoteSessionCapability.VIEW_ONLY);
            case CONSTRAINED_PTY -> DecodeResult.ok(RemoteSessionCapability.CONSTRAINED_PTY);
            default -> DecodeResult.reject("capability-not-pilot"); // UNSPECIFIED + UNRECOGNIZED + future
        };
    }

    /** Encode refuses a non-pilot capability outright — it must never reach the wire. */
    public static Capability encodeCapability(RemoteSessionCapability capability) {
        if (!WireContract.isPilotCapability(capability)) {
            throw new IllegalArgumentException("non-pilot capability cannot be encoded: " + capability);
        }
        return switch (capability) {
            case VIEW_ONLY -> Capability.VIEW_ONLY;
            case CONSTRAINED_PTY -> Capability.CONSTRAINED_PTY;
            default -> throw new IllegalArgumentException("unreachable: " + capability);
        };
    }

    /**
     * A whole capability list decodes only if EVERY element is pilot-enabled (one bad element rejects the
     * set — fail-closed, no silent dropping). {@code requireNonEmpty} enforces the SessionRequest rule that
     * an empty repeated field (the proto3 default!) is not a valid request.
     */
    public static DecodeResult<Set<RemoteSessionCapability>> decodeCapabilities(List<Capability> capabilities,
                                                                                boolean requireNonEmpty) {
        if (capabilities == null || capabilities.isEmpty()) {
            return requireNonEmpty ? DecodeResult.reject("capabilities-empty") : DecodeResult.ok(Set.of());
        }
        Set<RemoteSessionCapability> decoded = new LinkedHashSet<>();
        for (Capability capability : capabilities) {
            DecodeResult<RemoteSessionCapability> one = decodeCapability(capability);
            if (!one.isOk()) {
                return DecodeResult.reject(one.rejectReason());
            }
            decoded.add(one.orElseThrow());
        }
        return DecodeResult.ok(Set.copyOf(decoded));
    }

    /** Pilot operations only ({@code SCREEN_VIEW}, {@code PTY_COMMAND}); everything else rejects. */
    public static DecodeResult<RemoteOperation> decodeOperation(WireOperation operation) {
        if (operation == null) {
            return DecodeResult.reject("operation-null");
        }
        return switch (operation) {
            case SCREEN_VIEW -> DecodeResult.ok(RemoteOperation.SCREEN_VIEW);
            case PTY_COMMAND -> DecodeResult.ok(RemoteOperation.PTY_COMMAND);
            default -> DecodeResult.reject("operation-not-pilot");
        };
    }

    public static WireOperation encodeOperation(RemoteOperation operation) {
        return switch (operation) {
            case SCREEN_VIEW -> WireOperation.SCREEN_VIEW;
            case PTY_COMMAND -> WireOperation.PTY_COMMAND;
            default -> throw new IllegalArgumentException("non-pilot operation cannot be encoded: " + operation);
        };
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    /** AgentHello stays ADVISORY (never an authorization input) — but it still decodes fail-closed. */
    public static DecodeResult<RemoteBridgeMessages.AgentHello> decode(AgentHello hello) {
        if (hello == null) {
            return DecodeResult.reject("agent-hello-null");
        }
        if (!WireContract.isValidId(hello.getDeviceId())) {
            return DecodeResult.reject("agent-hello-device-id");
        }
        if (!isSafeText(hello.getAgentVersion(), true) || !isSafeText(hello.getProtocolVersion(), true)
                || !isSafeText(hello.getCertFingerprint(), true)) {
            return DecodeResult.reject("agent-hello-text");
        }
        if (hello.getAttestationEvidenceB64().isEmpty()) {
            return DecodeResult.reject("agent-hello-attestation-missing");
        }
        DecodeResult<Set<RemoteSessionCapability>> advertised =
                decodeCapabilities(hello.getAdvertisedCapabilitiesList(), false);
        if (!advertised.isOk()) {
            return DecodeResult.reject(advertised.rejectReason());
        }
        return DecodeResult.ok(new RemoteBridgeMessages.AgentHello(hello.getAgentVersion(), hello.getDeviceId(),
                hello.getCertFingerprint(), hello.getAttestationEvidenceB64(), hello.getProtocolVersion(),
                advertised.orElseThrow()));
    }

    public static AgentHello encode(RemoteBridgeMessages.AgentHello hello) {
        AgentHello.Builder builder = AgentHello.newBuilder()
                .setAgentVersion(nullToEmpty(hello.agentVersion()))
                .setDeviceId(nullToEmpty(hello.deviceId()))
                .setCertFingerprint(nullToEmpty(hello.certFingerprint()))
                .setAttestationEvidenceB64(nullToEmpty(hello.attestationEvidenceB64()))
                .setProtocolVersion(nullToEmpty(hello.protocolVersion()));
        hello.advertisedCapabilities().forEach(c -> builder.addAdvertisedCapabilities(encodeCapability(c)));
        return builder.build();
    }

    public static DecodeResult<RemoteBridgeMessages.SessionRequest> decode(SessionRequest request) {
        if (request == null) {
            return DecodeResult.reject("session-request-null");
        }
        DecodeResult<Set<RemoteSessionCapability>> capabilities =
                decodeCapabilities(request.getRequestedCapabilitiesList(), true);
        if (!capabilities.isOk()) {
            return DecodeResult.reject(capabilities.rejectReason());
        }
        if (!isSafeText(request.getReason(), false)) {
            return DecodeResult.reject("session-request-reason");
        }
        RemoteBridgeMessages.SessionRequest decoded = new RemoteBridgeMessages.SessionRequest(
                request.getSessionId(), request.getDeviceId(), request.getOperatorSubject(),
                emptyToNull(request.getReason()), capabilities.orElseThrow());
        // the T-1 composite validator owns the id + capability rules — single source of truth
        if (!WireContract.isValid(decoded)) {
            return DecodeResult.reject("session-request-invalid");
        }
        return DecodeResult.ok(decoded);
    }

    public static SessionRequest encode(RemoteBridgeMessages.SessionRequest request) {
        SessionRequest.Builder builder = SessionRequest.newBuilder()
                .setSessionId(nullToEmpty(request.sessionId()))
                .setDeviceId(nullToEmpty(request.deviceId()))
                .setOperatorSubject(nullToEmpty(request.operatorSubject()))
                .setReason(nullToEmpty(request.reason()));
        request.requestedCapabilities().forEach(c -> builder.addRequestedCapabilities(encodeCapability(c)));
        return builder.build();
    }

    public static DecodeResult<RemoteBridgeMessages.ConsentPrompt> decode(ConsentPrompt prompt) {
        if (prompt == null) {
            return DecodeResult.reject("consent-prompt-null");
        }
        if (!WireContract.isValidId(prompt.getSessionId())) {
            return DecodeResult.reject("consent-prompt-session-id");
        }
        if (!isSafeText(prompt.getOperatorDisplayName(), true) || !isSafeText(prompt.getReason(), false)) {
            return DecodeResult.reject("consent-prompt-text");
        }
        DecodeResult<Set<RemoteSessionCapability>> capabilities =
                decodeCapabilities(prompt.getCapabilitiesList(), true);
        if (!capabilities.isOk()) {
            return DecodeResult.reject(capabilities.rejectReason());
        }
        if (prompt.getExpiryEpochMillis() <= 0) {
            return DecodeResult.reject("consent-prompt-expiry");
        }
        return DecodeResult.ok(new RemoteBridgeMessages.ConsentPrompt(prompt.getSessionId(),
                prompt.getOperatorDisplayName(), emptyToNull(prompt.getReason()), capabilities.orElseThrow(),
                prompt.getExpiryEpochMillis()));
    }

    public static ConsentPrompt encode(RemoteBridgeMessages.ConsentPrompt prompt) {
        ConsentPrompt.Builder builder = ConsentPrompt.newBuilder()
                .setSessionId(nullToEmpty(prompt.sessionId()))
                .setOperatorDisplayName(nullToEmpty(prompt.operatorDisplayName()))
                .setReason(nullToEmpty(prompt.reason()))
                .setExpiryEpochMillis(prompt.expiryEpochMillis());
        prompt.capabilities().forEach(c -> builder.addCapabilities(encodeCapability(c)));
        return builder.build();
    }

    public static DecodeResult<RemoteBridgeMessages.ConsentResult> decode(ConsentResult result) {
        if (result == null) {
            return DecodeResult.reject("consent-result-null");
        }
        if (!WireContract.isValidId(result.getSessionId())) {
            return DecodeResult.reject("consent-result-session-id");
        }
        // a Windows interactive session name ("Console", "RDP-Tcp#0") is free-er than a wire id
        if (!isSafeText(result.getWindowsInteractiveSession(), true)) {
            return DecodeResult.reject("consent-result-interactive-session");
        }
        if (result.getGrantedAtEpochMillis() < 0 || result.getExpiryEpochMillis() < 0) {
            return DecodeResult.reject("consent-result-times");
        }
        return DecodeResult.ok(new RemoteBridgeMessages.ConsentResult(result.getSessionId(), result.getGranted(),
                result.getWindowsInteractiveSession(), result.getGrantedAtEpochMillis(),
                result.getExpiryEpochMillis()));
    }

    public static ConsentResult encode(RemoteBridgeMessages.ConsentResult result) {
        return ConsentResult.newBuilder()
                .setSessionId(nullToEmpty(result.sessionId()))
                .setGranted(result.granted())
                .setWindowsInteractiveSession(nullToEmpty(result.windowsInteractiveSession()))
                .setGrantedAtEpochMillis(result.grantedAtEpochMillis())
                .setExpiryEpochMillis(result.expiryEpochMillis())
                .build();
    }

    /**
     * The raw command line travels ONLY here (a permit carries the canonical hash). Empty normalises to null
     * (proto3 default); whether a PTY operation REQUIRES a command stays the broker's rule, not the wire's.
     */
    public static DecodeResult<RemoteBridgeMessages.OperationRequest> decode(OperationRequest request) {
        if (request == null) {
            return DecodeResult.reject("operation-request-null");
        }
        if (!WireContract.isValidId(request.getSessionId())) {
            return DecodeResult.reject("operation-request-session-id");
        }
        if (!WireContract.isValidId(request.getOperationId())) {
            return DecodeResult.reject("operation-request-operation-id");
        }
        DecodeResult<RemoteOperation> operation = decodeOperation(request.getOperation());
        if (!operation.isOk()) {
            return DecodeResult.reject(operation.rejectReason());
        }
        return DecodeResult.ok(new RemoteBridgeMessages.OperationRequest(request.getSessionId(),
                request.getOperationId(), operation.orElseThrow(), emptyToNull(request.getCommandLine())));
    }

    public static OperationRequest encode(RemoteBridgeMessages.OperationRequest request) {
        return OperationRequest.newBuilder()
                .setSessionId(nullToEmpty(request.sessionId()))
                .setOperationId(nullToEmpty(request.operationId()))
                .setOperation(encodeOperation(request.operation()))
                .setCommandLine(nullToEmpty(request.commandLine()))
                .build();
    }

    public static DecodeResult<RemoteBridgeMessages.Kill> decode(Kill kill) {
        if (kill == null) {
            return DecodeResult.reject("kill-null");
        }
        if (!WireContract.isValidId(kill.getSessionId())) {
            return DecodeResult.reject("kill-session-id");
        }
        if (!isSafeText(kill.getKillReason(), true)) {
            return DecodeResult.reject("kill-reason");
        }
        if (kill.getIssuedAtEpochMillis() <= 0) {
            return DecodeResult.reject("kill-issued-at");
        }
        return DecodeResult.ok(new RemoteBridgeMessages.Kill(kill.getSessionId(), kill.getKillReason(),
                kill.getIssuedAtEpochMillis()));
    }

    public static Kill encode(RemoteBridgeMessages.Kill kill) {
        return Kill.newBuilder()
                .setSessionId(nullToEmpty(kill.sessionId()))
                .setKillReason(nullToEmpty(kill.killReason()))
                .setIssuedAtEpochMillis(kill.issuedAtEpochMillis())
                .build();
    }

    public static DecodeResult<RemoteBridgeMessages.AuditEvent> decode(AuditEvent event) {
        if (event == null) {
            return DecodeResult.reject("audit-event-null");
        }
        if (!WireContract.isValidId(event.getSessionId())) {
            return DecodeResult.reject("audit-event-session-id");
        }
        // event types are broker-composed ("ALLOW_DECISION:<opId>", "DENY:<gate>") — safe text, not a wire id
        if (!isSafeText(event.getEventType(), true)) {
            return DecodeResult.reject("audit-event-type");
        }
        String contentHash = event.getContentHash();
        if (!contentHash.isEmpty() && !COMMAND_HASH.matcher(contentHash).matches()) {
            return DecodeResult.reject("audit-event-content-hash");
        }
        if (event.getEpochMillis() <= 0) {
            return DecodeResult.reject("audit-event-time");
        }
        return DecodeResult.ok(new RemoteBridgeMessages.AuditEvent(event.getSessionId(), event.getEventType(),
                contentHash, event.getEpochMillis()));
    }

    public static AuditEvent encode(RemoteBridgeMessages.AuditEvent event) {
        return AuditEvent.newBuilder()
                .setSessionId(nullToEmpty(event.sessionId()))
                .setEventType(nullToEmpty(event.eventType()))
                .setContentHash(nullToEmpty(event.contentHash()))
                .setEpochMillis(event.epochMillis())
                .build();
    }

    // ------------------------------------------------------------------
    // OperationPermit — the signed artifact; strictest decode on the wire
    // ------------------------------------------------------------------

    /**
     * Strict decode: schema-version pin, alg pin, id allowlists, pilot capability, capability↔commandHash
     * consistency (the signer's invariant, re-enforced at the wire), positive validity window, positive
     * seq, base64-checked signature. The decoded record's {@link OperationPermit#canonicalPayload()} is
     * byte-identical to the encoded one's, so the broker's signature still verifies.
     */
    public static DecodeResult<OperationPermit> decode(
            com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit permit) {
        if (permit == null) {
            return DecodeResult.reject("permit-null");
        }
        if (permit.getPermitVersion() != RemoteBridgePermitSigner.PERMIT_VERSION) {
            return DecodeResult.reject("permit-version");
        }
        if (!RemoteBridgePermitSigner.PERMIT_ALG.equals(permit.getAlg())) {
            return DecodeResult.reject("permit-alg");
        }
        if (!WireContract.isValidId(permit.getKid()) || !WireContract.isValidId(permit.getPolicyVersion())
                || !WireContract.isValidId(permit.getSessionId()) || !WireContract.isValidId(permit.getOperationId())
                || !WireContract.isValidId(permit.getDeviceId())
                || !WireContract.isValidId(permit.getOperatorSubject())) {
            return DecodeResult.reject("permit-id");
        }
        if (!DECISION_ID.matcher(permit.getDecisionId()).matches()) {
            return DecodeResult.reject("permit-decision-id");
        }
        DecodeResult<RemoteSessionCapability> capability = decodeCapability(permit.getCapability());
        if (!capability.isOk()) {
            return DecodeResult.reject(capability.rejectReason());
        }
        String commandHash = permit.getCommandHash();
        boolean pty = capability.orElseThrow() == RemoteSessionCapability.CONSTRAINED_PTY;
        if (pty && !COMMAND_HASH.matcher(commandHash).matches()) {
            return DecodeResult.reject("permit-command-hash-missing");
        }
        if (!pty && !commandHash.isEmpty()) {
            return DecodeResult.reject("permit-command-hash-unexpected");
        }
        if (permit.getIssuedAtEpochMillis() <= 0
                || permit.getExpiresAtEpochMillis() <= permit.getIssuedAtEpochMillis()) {
            return DecodeResult.reject("permit-validity-window");
        }
        if (permit.getSeq() <= 0) {
            return DecodeResult.reject("permit-seq");
        }
        String signature = permit.getSignatureB64();
        if (signature.isEmpty()) {
            return DecodeResult.reject("permit-signature-missing"); // an unsigned permit never travels
        }
        try {
            Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return DecodeResult.reject("permit-signature-encoding");
        }
        return DecodeResult.ok(new OperationPermit(permit.getAlg(), permit.getKid(), permit.getPermitVersion(),
                permit.getPolicyVersion(), permit.getDecisionId(), permit.getSessionId(), permit.getOperationId(),
                permit.getDeviceId(), permit.getOperatorSubject(), capability.orElseThrow(), commandHash,
                permit.getIssuedAtEpochMillis(), permit.getExpiresAtEpochMillis(), permit.getSeq(), signature));
    }

    public static com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit encode(
            OperationPermit permit) {
        return com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit.newBuilder()
                .setAlg(nullToEmpty(permit.alg()))
                .setKid(nullToEmpty(permit.kid()))
                .setPermitVersion(permit.permitVersion())
                .setPolicyVersion(nullToEmpty(permit.policyVersion()))
                .setDecisionId(nullToEmpty(permit.decisionId()))
                .setSessionId(nullToEmpty(permit.sessionId()))
                .setOperationId(nullToEmpty(permit.operationId()))
                .setDeviceId(nullToEmpty(permit.deviceId()))
                .setOperatorSubject(nullToEmpty(permit.operatorSubject()))
                .setCapability(encodeCapability(permit.capability()))
                .setCommandHash(nullToEmpty(permit.commandHash()))
                .setIssuedAtEpochMillis(permit.issuedAtEpochMillis())
                .setExpiresAtEpochMillis(permit.expiresAtEpochMillis())
                .setSeq(permit.seq())
                .setSignatureB64(nullToEmpty(permit.signatureB64()))
                .build();
    }

    // ------------------------------------------------------------------
    // OperationDispatch — broker→agent CONSTRAINED_PTY command transport (T-4)
    // ------------------------------------------------------------------

    /**
     * Decodes the broker→agent command dispatch. The INNER permit goes through the strict permit decoder above
     * (so its {@link OperationPermit#canonicalPayload()} — and thus the broker signature — survives the
     * round-trip byte-for-byte; the wrapper never re-marshals the signed bytes). Then it enforces the
     * wire-level capability↔command consistency, mirroring the permit's capability↔commandHash rule: a
     * {@code CONSTRAINED_PTY} permit REQUIRES a non-blank {@code command_line}; a {@code VIEW_ONLY} permit
     * REQUIRES an empty one. The signature verification and the hash-match
     * ({@code CanonicalCommand.hash(commandLine) == permit.commandHash}) are the agent's crypto gate, NOT the
     * wire adapter's job.
     */
    public static DecodeResult<RemoteBridgeMessages.OperationDispatch> decode(
            com.example.endpointadmin.remoteaccess.bridge.proto.OperationDispatch dispatch) {
        if (dispatch == null) {
            return DecodeResult.reject("operation-dispatch-null");
        }
        if (!dispatch.hasPermit()) {
            return DecodeResult.reject("operation-dispatch-permit-missing");
        }
        DecodeResult<OperationPermit> permit = decode(dispatch.getPermit());
        if (!permit.isOk()) {
            return DecodeResult.reject(permit.rejectReason());
        }
        OperationPermit decoded = permit.orElseThrow();
        String commandLine = dispatch.getCommandLine();
        boolean pty = decoded.capability() == RemoteSessionCapability.CONSTRAINED_PTY;
        if (pty) {
            if (commandLine.isBlank()) {
                return DecodeResult.reject("operation-dispatch-command-missing"); // whitespace-only is not a command
            }
            // Defense-in-depth (Codex 019ecd07): the hash-match + the agent allowlist are the authoritative gate,
            // but a bounded, control-char-free command means a NUL/newline/over-long command never reaches a log
            // or exec path from the wire. isSafeText = non-empty + <=256 chars + no control chars.
            if (!isSafeText(commandLine, true)) {
                return DecodeResult.reject("operation-dispatch-command-unsafe");
            }
        } else if (!commandLine.isEmpty()) {
            return DecodeResult.reject("operation-dispatch-command-unexpected"); // VIEW_ONLY carries no command
        }
        // PTY: keep the command EXACT (CanonicalCommand canonicalises for the hash; the wire carries it raw).
        // VIEW_ONLY: empty normalises to null.
        return DecodeResult.ok(new RemoteBridgeMessages.OperationDispatch(decoded, emptyToNull(commandLine)));
    }

    public static com.example.endpointadmin.remoteaccess.bridge.proto.OperationDispatch encode(
            RemoteBridgeMessages.OperationDispatch dispatch) {
        return com.example.endpointadmin.remoteaccess.bridge.proto.OperationDispatch.newBuilder()
                .setPermit(encode(dispatch.permit()))
                .setCommandLine(nullToEmpty(dispatch.commandLine()))
                .build();
    }

    // ------------------------------------------------------------------
    // Envelope — channel/payload compatibility (the stream rule, statically enforced)
    // ------------------------------------------------------------------

    /**
     * Envelope-level wire rules (T-2a static validation; stream runtime is T-2b):
     * <ul>
     *   <li>channel must be explicit ({@code CHANNEL_TYPE_UNSPECIFIED}/{@code UNRECOGNIZED} reject);</li>
     *   <li>a payload must be set;</li>
     *   <li>{@code data_frame} travels ONLY on DATA; {@code heartbeat}/{@code error} on either; every other
     *       payload ONLY on CONTROL — so a KILL can never legally sit on (or behind) the DATA stream;</li>
     *   <li>the payloads with no domain-record decoder ({@code data_frame}/{@code heartbeat}/{@code error})
     *       validate their CONTENT here too — this method is the only static gate they pass (Codex P1);</li>
     *   <li>routing headers are optional (empty until a session exists) but validate when present;</li>
     *   <li>{@code frame_seq}/{@code sent_at_epoch_millis} are non-negative.</li>
     * </ul>
     */
    public static DecodeResult<Envelope> validateEnvelope(Envelope envelope) {
        if (envelope == null) {
            return DecodeResult.reject("envelope-null");
        }
        ChannelType channel = envelope.getChannelType();
        if (channel != ChannelType.CONTROL && channel != ChannelType.DATA) {
            return DecodeResult.reject("envelope-channel");
        }
        Envelope.PayloadCase payload = envelope.getPayloadCase();
        if (payload == Envelope.PayloadCase.PAYLOAD_NOT_SET) {
            return DecodeResult.reject("envelope-payload-missing");
        }
        boolean allowed = switch (payload) {
            case DATA_FRAME -> channel == ChannelType.DATA;
            case HEARTBEAT, ERROR -> true;
            default -> channel == ChannelType.CONTROL;
        };
        if (!allowed) {
            return DecodeResult.reject("envelope-payload-channel-mismatch");
        }
        String payloadDefect = switch (payload) {
            case DATA_FRAME -> dataFrameDefect(envelope.getDataFrame());
            case HEARTBEAT -> heartbeatDefect(envelope.getHeartbeat());
            case ERROR -> errorFrameDefect(envelope.getError());
            default -> null; // CONTROL payloads have their own decode(...) validators
        };
        if (payloadDefect != null) {
            return DecodeResult.reject(payloadDefect);
        }
        if (!envelope.getSessionId().isEmpty() && !WireContract.isValidId(envelope.getSessionId())) {
            return DecodeResult.reject("envelope-session-id");
        }
        if (!envelope.getDeviceId().isEmpty() && !WireContract.isValidId(envelope.getDeviceId())) {
            return DecodeResult.reject("envelope-device-id");
        }
        if (!envelope.getStreamId().isEmpty() && !WireContract.isValidId(envelope.getStreamId())) {
            return DecodeResult.reject("envelope-stream-id");
        }
        if (envelope.getFrameSeq() < 0 || envelope.getSentAtEpochMillis() < 0) {
            return DecodeResult.reject("envelope-counters");
        }
        return DecodeResult.ok(envelope);
    }

    /** Frame byte-size limits stay a T-2b stream-layer concern (no config in T-2a); shape validates here. */
    private static String dataFrameDefect(com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame frame) {
        if (!WireContract.isValidId(frame.getStreamId())) {
            return "data-frame-stream-id";
        }
        if (frame.getFrameSeq() < 0) {
            return "data-frame-seq";
        }
        if (!isSafeText(frame.getContentType(), true)) {
            return "data-frame-content-type";
        }
        return null;
    }

    private static String heartbeatDefect(com.example.endpointadmin.remoteaccess.bridge.proto.Heartbeat heartbeat) {
        if (heartbeat.getHeartbeatIntervalMillis() <= 0) {
            return "heartbeat-interval";
        }
        if (heartbeat.getLeaseExpiresAtEpochMillis() < 0) {
            return "heartbeat-lease";
        }
        if (!isSafeText(heartbeat.getProtocolVersion(), false)) {
            return "heartbeat-protocol-version";
        }
        return null;
    }

    private static String errorFrameDefect(com.example.endpointadmin.remoteaccess.bridge.proto.ErrorFrame error) {
        if (!isSafeText(error.getCode(), true)) {
            return "error-frame-code";
        }
        if (!isSafeText(error.getDetail(), false)) {
            return "error-frame-detail";
        }
        return null;
    }
}
