package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Faz 22.6 #548 slice-1 step-2 (Codex {@code 019efada}) — the TPM-native session device-key evidence, decoded
 * from a wire-validated {@code RemoteBridgeMessages.DeviceKeyAttestationResponse}. This is the <b>canonical
 * #548</b> evidence family (the broker-nonced, live challenge-response): TPM {@code Certify}/{@code Quote} +
 * AK/EK material + the device-key signature over the binding context.
 *
 * <p>It is deliberately DISTINCT from {@code DeviceIdentityVerifier.DeviceKeyAttestation} (the #732 CA-static,
 * AgentHello-carried path) and is NEVER stored in {@code PeerEvidenceParser.ParsedEvidence#deviceKey}. The two
 * evidence families must never collapse under one {@code deviceTrusted} boolean (see the device-key session
 * attestation design doc §6).
 *
 * <p><b>Shape-only, not trust.</b> A populated record means the response decoded into byte arrays with the
 * required core members present — it does NOT mean the attestation verified. The forthcoming
 * {@code DEVICE_KEY_ATTESTATION_REAL} verifier re-derives the canonical binding context from the stored
 * challenge + the live mTLS peer, matches {@code deviceKeyPub} to the live leaf key, runs
 * {@code TpmAttestationVerifier.verifyCertify}/{@code verifyQuote}, validates the EK chain, and binds AK&harr;EK
 * via the stored enrollment record before establishing any trust.
 *
 * <p>{@code ekPub}/{@code ekCert}/{@code ekCertChain} are OPTIONAL (empty for the bounded-lab path; required by
 * the verifier's strong-path policy).
 *
 * <p><b>Deeply immutable:</b> this record will become a challenge/session-store value and a verifier input, so
 * it defensively clones every byte array on the way in (compact constructor) and on the way out (accessors),
 * and deep-copies {@code ekCertChain} — no caller can mutate the stored evidence (no TOCTOU), matching the
 * clone discipline of the local {@code tpmattest} primitives.
 */
public record TpmDeviceKeySessionAttestation(
        String challengeId,
        String schema,
        byte[] deviceKeyPub,
        byte[] akPub,
        byte[] akName,
        byte[] ekPub,
        byte[] ekCert,
        List<byte[]> ekCertChain,
        byte[] certifyInfo,
        byte[] certifySig,
        byte[] quote,
        byte[] quoteSig,
        byte[] bindingContext,
        byte[] deviceKeySig,
        long signedAtEpochMillis) {

    private static final byte[] EMPTY = new byte[0];

    public TpmDeviceKeySessionAttestation {
        deviceKeyPub = copy(deviceKeyPub);
        akPub = copy(akPub);
        akName = copy(akName);
        ekPub = copy(ekPub);
        ekCert = copy(ekCert);
        ekCertChain = deepCopy(ekCertChain);
        certifyInfo = copy(certifyInfo);
        certifySig = copy(certifySig);
        quote = copy(quote);
        quoteSig = copy(quoteSig);
        bindingContext = copy(bindingContext);
        deviceKeySig = copy(deviceKeySig);
    }

    @Override
    public byte[] deviceKeyPub() {
        return deviceKeyPub.clone();
    }

    @Override
    public byte[] akPub() {
        return akPub.clone();
    }

    @Override
    public byte[] akName() {
        return akName.clone();
    }

    @Override
    public byte[] ekPub() {
        return ekPub.clone();
    }

    @Override
    public byte[] ekCert() {
        return ekCert.clone();
    }

    @Override
    public List<byte[]> ekCertChain() {
        return deepCopy(ekCertChain);
    }

    @Override
    public byte[] certifyInfo() {
        return certifyInfo.clone();
    }

    @Override
    public byte[] certifySig() {
        return certifySig.clone();
    }

    @Override
    public byte[] quote() {
        return quote.clone();
    }

    @Override
    public byte[] quoteSig() {
        return quoteSig.clone();
    }

    @Override
    public byte[] bindingContext() {
        return bindingContext.clone();
    }

    @Override
    public byte[] deviceKeySig() {
        return deviceKeySig.clone();
    }

    /**
     * The required (non-optional) members are present. The EK material ({@code ekPub}/{@code ekCert}/
     * {@code ekCertChain}) may still be empty — that is the bounded-lab path; the verifier's strong-path policy
     * is what REQUIRES the EK material, not this shape check. Reads the backing fields directly (no clone churn).
     */
    public boolean hasRequiredCoreEvidence() {
        return challengeId != null && !challengeId.isBlank()
                && schema != null && !schema.isBlank()
                && deviceKeyPub.length > 0
                && akPub.length > 0
                && akName.length > 0
                && certifyInfo.length > 0
                && certifySig.length > 0
                && quote.length > 0
                && quoteSig.length > 0
                && bindingContext.length > 0
                && deviceKeySig.length > 0;
    }

    private static byte[] copy(byte[] value) {
        return value == null || value.length == 0 ? EMPTY : value.clone();
    }

    private static List<byte[]> deepCopy(List<byte[]> chain) {
        if (chain == null || chain.isEmpty()) {
            return List.of();
        }
        List<byte[]> out = new ArrayList<>(chain.size());
        for (byte[] entry : chain) {
            out.add(entry == null ? EMPTY : entry.clone());
        }
        return Collections.unmodifiableList(out);
    }
}
