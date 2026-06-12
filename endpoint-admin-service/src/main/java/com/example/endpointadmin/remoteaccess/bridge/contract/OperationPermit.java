package com.example.endpointadmin.remoteaccess.bridge.contract;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Faz 22.6 T-1a — the short-lived, broker-signed authorization for ONE operation (ADR-0038 §5). The broker
 * issues a permit only when {@code RemoteSessionPolicyEngine} ALLOWs; the agent verifies the broker's signature
 * and runs exactly the permitted canonical command (the {@code commandHash}), within {@code expiresAtEpochMillis}.
 * The agent can only VERIFY a permit — it cannot mint one (broker-private / agent-public asymmetric key,
 * Codex 019eb9fb), so a compromised endpoint cannot forge local authorization.
 *
 * <p>The {@link #canonicalPayload()} is the stable, length-prefixed byte sequence the broker SIGNS — it covers
 * every security field and is independent of Java {@code toString()} AND of the future protobuf binary
 * encoding (so the wire format can change in T-2 without changing what is signed). The {@code signatureB64} is
 * set by the T-1b signer and is NOT part of the signed payload.
 *
 * @param alg                signature algorithm (allowlisted; e.g. {@code SHA256withECDSA})
 * @param kid                key id of the broker's permit-signing key (rotation)
 * @param permitVersion      permit schema version
 * @param policyVersion      the policy version that produced the decision
 * @param decisionId         the broker decision this permit realises
 * @param sessionId          the remote-support session
 * @param operationId        the specific operation
 * @param deviceId           the target endpoint
 * @param operatorSubject    the operator the permit is bound to
 * @param capability         the capability exercised (pilot: VIEW_ONLY or CONSTRAINED_PTY)
 * @param commandHash        {@link CanonicalCommand#hash()} for a PTY command; empty for a non-command op
 * @param issuedAtEpochMillis issuance time
 * @param expiresAtEpochMillis short expiry — the agent refuses an expired permit
 * @param seq                monotonic per-session sequence (replay guard)
 * @param signatureB64       the broker signature over {@link #canonicalPayload()} (set by the signer; not signed)
 */
public record OperationPermit(String alg,
                              String kid,
                              int permitVersion,
                              String policyVersion,
                              String decisionId,
                              String sessionId,
                              String operationId,
                              String deviceId,
                              String operatorSubject,
                              RemoteSessionCapability capability,
                              String commandHash,
                              long issuedAtEpochMillis,
                              long expiresAtEpochMillis,
                              long seq,
                              String signatureB64) {

    private static final String DOMAIN = "RemoteBridgeOperationPermit:v1";

    /** True when {@code now} is within {@code [issuedAt, expiresAt)} — the agent enforces this too. */
    public boolean isFresh(long nowEpochMillis) {
        return nowEpochMillis >= issuedAtEpochMillis && nowEpochMillis < expiresAtEpochMillis;
    }

    /**
     * The stable byte sequence the broker signs — every security field under a domain tag, length-prefixed
     * (delimiter-safe), EXCLUDING the signature. Independent of {@code toString()} and of the protobuf wire
     * encoding (T-2 adapters must reproduce these exact bytes).
     */
    public byte[] canonicalPayload() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            writeField(dos, DOMAIN);
            writeField(dos, alg);
            writeField(dos, kid);
            dos.writeInt(permitVersion);
            writeField(dos, policyVersion);
            writeField(dos, decisionId);
            writeField(dos, sessionId);
            writeField(dos, operationId);
            writeField(dos, deviceId);
            writeField(dos, operatorSubject);
            writeField(dos, capability == null ? "" : capability.name());
            writeField(dos, commandHash);
            dos.writeLong(issuedAtEpochMillis);
            dos.writeLong(expiresAtEpochMillis);
            dos.writeLong(seq);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never throws
        }
        return out.toByteArray();
    }

    /** A copy with the signature set (used by the T-1b signer). */
    public OperationPermit withSignature(String signatureB64) {
        return new OperationPermit(alg, kid, permitVersion, policyVersion, decisionId, sessionId, operationId,
                deviceId, operatorSubject, capability, commandHash, issuedAtEpochMillis, expiresAtEpochMillis,
                seq, signatureB64);
    }

    private static void writeField(DataOutputStream dos, String field) throws IOException {
        byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }
}
