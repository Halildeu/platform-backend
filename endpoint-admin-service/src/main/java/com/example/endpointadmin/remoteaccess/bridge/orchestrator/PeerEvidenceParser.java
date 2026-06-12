package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.AttestationEvidence;
import com.example.endpointadmin.remoteaccess.CertRef;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;

import java.util.Optional;

/**
 * Faz 22.6 T-4a-i (Codex 019ebbfa P3) — decodes an authenticated peer's hello into the STRUCTURED inputs the
 * B1.4 verifiers require ({@link CertRef} from the mTLS chain, {@link AttestationEvidence} and
 * {@link DeviceIdentityVerifier.DeviceKeyAttestation} from {@code attestationEvidenceB64}). The hello fields
 * are NEVER authority — they are only candidate INPUTS to verifiers; anything unparseable yields
 * {@link ParsedEvidence#empty()}, which the {@link PeerTrustLedger} records as all-false (fail-closed).
 *
 * <p>{@link #FAIL_CLOSED} is the default until the real wire-format decoder lands (a later slice): it parses
 * NOTHING, so no verifier ever sees attacker-shaped bytes and every trust boolean stays false.
 */
public interface PeerEvidenceParser {

    /** Structured verifier inputs; an absent member simply means "no evidence presented" (→ false). */
    record ParsedEvidence(Optional<CertRef> certRef,
                          Optional<AttestationEvidence> attestation,
                          Optional<DeviceIdentityVerifier.DeviceKeyAttestation> deviceKey) {

        public ParsedEvidence {
            certRef = certRef == null ? Optional.empty() : certRef;
            attestation = attestation == null ? Optional.empty() : attestation;
            deviceKey = deviceKey == null ? Optional.empty() : deviceKey;
        }

        public static ParsedEvidence empty() {
            return new ParsedEvidence(Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** MUST be total: a malformed hello returns {@link ParsedEvidence#empty()}, never throws. */
    ParsedEvidence parse(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello);

    /** The default until the real decoder slice: presents nothing, so every trust boolean is false. */
    PeerEvidenceParser FAIL_CLOSED = (peer, hello) -> ParsedEvidence.empty();
}
