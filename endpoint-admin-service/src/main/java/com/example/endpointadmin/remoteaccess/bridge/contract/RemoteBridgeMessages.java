package com.example.endpointadmin.remoteaccess.bridge.contract;

import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;

import java.util.List;
import java.util.Set;

/**
 * Faz 22.6 T-1a — the remote-bridge WIRE CONTRACT message model, as pure Java records. These are designed to
 * map 1:1 onto the future protobuf (`RemoteBridgeService`, T-2) — the field correspondence + reserved proto
 * field numbers are documented in {@code docs/remote-bridge-wire-contract.md}. T-1 owns MEANING (the domain
 * records, canonicalisation, validation); T-2's protobuf adapters will own BYTES, replacing these on the wire
 * WITHOUT changing the broker / state-machine / permit-signer / policy-dry-run (the T-1 acceptance boundary,
 * Codex 019eb9fb).
 *
 * <p><b>Trust note:</b> {@link AgentHello} is AGENT-SUPPLIED and ADVISORY ONLY — its fields (advertised
 * capabilities, attestation evidence) are NEVER trusted as authorization input. The broker re-derives every
 * authoritative fact through the B1.4 verifiers into a separate, verifier-produced trust-evidence object
 * (T-1b), never off {@code AgentHello}.
 */
public final class RemoteBridgeMessages {

    private RemoteBridgeMessages() {
    }

    /** The two logical channels (ADR-0038 §4). Declarative in T-1 — the stream/never-drop semantics are T-2. */
    public enum ChannelType { CONTROL, DATA }

    /** Agent → broker on connect. ADVISORY ONLY — never an authorization input (see class note). */
    public record AgentHello(String agentVersion,
                             String deviceId,
                             String certFingerprint,
                             String attestationEvidenceB64,
                             String protocolVersion,
                             Set<RemoteSessionCapability> advertisedCapabilities) {
        public AgentHello {
            advertisedCapabilities = advertisedCapabilities == null ? Set.of() : Set.copyOf(advertisedCapabilities);
        }
    }

    /** Operator console → broker (the operator never talks to the agent directly). */
    public record SessionRequest(String sessionId,
                                 String deviceId,
                                 String operatorSubject,
                                 String reason,
                                 Set<RemoteSessionCapability> requestedCapabilities) {
        public SessionRequest {
            requestedCapabilities = requestedCapabilities == null ? Set.of() : Set.copyOf(requestedCapabilities);
        }
    }

    /** Broker → agent: prompt the endpoint user for attended consent (operator identity + reason + caps + notice). */
    public record ConsentPrompt(String sessionId,
                                String operatorDisplayName,
                                String reason,
                                Set<RemoteSessionCapability> capabilities,
                                long expiryEpochMillis) {
        public ConsentPrompt {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    /** Agent → broker: the endpoint user's consent outcome (becomes a {@link ConsentLease}). */
    public record ConsentResult(String sessionId,
                                boolean granted,
                                String windowsInteractiveSession,
                                long grantedAtEpochMillis,
                                long expiryEpochMillis) {
    }

    /** Operator console → broker: one attempted operation. {@code commandLine} is null for a non-PTY operation. */
    public record OperationRequest(String sessionId,
                                   String operationId,
                                   RemoteOperation operation,
                                   String commandLine) {
    }

    /**
     * Broker → agent (CONTROL, T-4 CONSTRAINED_PTY): a signed {@link OperationPermit} paired with the plaintext
     * command to execute. The raw command travels toward the agent ONLY here (never inside the permit, which
     * carries only the {@code commandHash}); it is authorization-NEUTRAL — the agent trusts it only after
     * re-deriving {@code CanonicalCommand.hash(commandLine)} equals {@code permit.commandHash()} against the
     * SIGNED permit. {@code commandLine} is null/empty for VIEW_ONLY (a non-command capability). The nested
     * permit's signed bytes are unchanged by this wrapper — verify the INNER permit, never the wrapper. (Wire
     * shadow-spec: {@code docs/remote-bridge-wire-contract.md}; Codex 019ecd07 B2.)
     */
    public record OperationDispatch(OperationPermit permit, String commandLine) {
    }

    /** Broker → operator/audit: the engine's verdict (maps {@code RemoteSessionPolicyEngine.SessionDecision}). */
    public record PolicyDecision(String sessionId,
                                 String operationId,
                                 String outcome,
                                 String gate,
                                 String detail) {
    }

    /** Broker → agent (CONTROL): terminate the session now. */
    public record Kill(String sessionId, String killReason, long issuedAtEpochMillis) {
    }

    /** Either side → the recorder: a control-plane audit event (metadata + content hash, never raw payload). */
    public record AuditEvent(String sessionId, String eventType, String contentHash, long epochMillis) {
    }

    /** Agent → broker: diagnostics for a failed local dispatch; broker records metadata only, never raw output. */
    public record AgentErrorFrame(String sessionId, String code, boolean retryable, String detail) {
    }

    /**
     * Faz 22.6 #548 Path A (DESIGN: {@code docs/faz22.6-device-key-session-attestation-design.md}; Codex 019efada).
     * Broker → agent (CONTROL): a one-shot, TTL-bounded device-key liveness challenge. The agent answers with a
     * {@link DeviceKeyAttestationResponse} signed over a canonical binding context derived from these fields, so a
     * copied response cannot be replayed (fresh nonce + short TTL + transport-peer binding). ADVISORY transport
     * frame; the broker's {@code DEVICE_KEY_ATTESTATION_REAL} verifier owns every authoritative decision.
     */
    public record DeviceKeyChallenge(String challengeId,
                                     String nonceB64,
                                     long issuedAtEpochMillis,
                                     long expiresAtEpochMillis,
                                     String transportPeerKey,
                                     String protocolVersion) {
    }

    /**
     * Faz 22.6 #548 Path A. Agent → broker (CONTROL): the fresh device-key session attestation answering a
     * {@link DeviceKeyChallenge}. ADVISORY shape only — the broker's {@code DEVICE_KEY_ATTESTATION_REAL} verifier
     * re-derives every fact: {@code deviceKeyPubB64} MUST equal the mTLS leaf public key; {@code deviceKeySigB64}
     * over the canonical binding context proves live possession; {@code certify*} proves TPM residency; {@code ek*}
     * chains to a pinned root (strong path). NEVER carries secrets (no activation secret / enrollment token /
     * private key).
     */
    public record DeviceKeyAttestationResponse(String challengeId,
                                               String schema,
                                               String deviceKeyPubB64,
                                               String akPubB64,
                                               String akNameB64,
                                               String ekPubB64,
                                               String ekCertB64,
                                               List<String> ekCertChainB64,
                                               String certifyInfoB64,
                                               String certifySigB64,
                                               String quoteB64,
                                               String quoteSigB64,
                                               String bindingContextB64,
                                               String deviceKeySigB64,
                                               long signedAtEpochMillis) {
        public DeviceKeyAttestationResponse {
            ekCertChainB64 = ekCertChainB64 == null ? List.of() : List.copyOf(ekCertChainB64);
        }
    }
}
