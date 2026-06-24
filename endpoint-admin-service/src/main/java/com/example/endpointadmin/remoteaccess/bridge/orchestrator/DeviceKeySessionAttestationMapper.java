package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Faz 22.6 #548 slice-1 step-2 (Codex {@code 019efada}) — maps a wire-validated
 * {@code RemoteBridgeMessages.DeviceKeyAttestationResponse} into the TPM-native
 * {@link TpmDeviceKeySessionAttestation} (base64 &rarr; bytes). SHAPE-ONLY: it decodes and checks
 * required-present, fail-closed; it sets NO trust. The {@code bridge.wire} adapter already validated the
 * base64 + bounds on decode; this is the layer below that turns the domain record into the byte arrays the
 * forthcoming {@code DEVICE_KEY_ATTESTATION_REAL} verifier consumes.
 *
 * <p>Total + fail-closed: any decode failure, a missing required member, or a blank EK-chain entry yields
 * {@link Optional#empty()} — never a throw, never a partially-populated record the verifier could misread.
 */
public final class DeviceKeySessionAttestationMapper {

    private DeviceKeySessionAttestationMapper() {
    }

    /**
     * @return the decoded TPM-native evidence, or {@link Optional#empty()} if the response is null, a required
     *         field is missing/blank/empty, or any base64 member is malformed. Never throws.
     */
    public static Optional<TpmDeviceKeySessionAttestation> map(
            RemoteBridgeMessages.DeviceKeyAttestationResponse response) {
        if (response == null || isBlank(response.challengeId()) || isBlank(response.schema())) {
            return Optional.empty();
        }
        try {
            byte[] deviceKeyPub = decodeRequired(response.deviceKeyPubB64());
            byte[] akPub = decodeRequired(response.akPubB64());
            byte[] akName = decodeRequired(response.akNameB64());
            byte[] certifyInfo = decodeRequired(response.certifyInfoB64());
            byte[] certifySig = decodeRequired(response.certifySigB64());
            byte[] quote = decodeRequired(response.quoteB64());
            byte[] quoteSig = decodeRequired(response.quoteSigB64());
            byte[] bindingContext = decodeRequired(response.bindingContextB64());
            byte[] deviceKeySig = decodeRequired(response.deviceKeySigB64());
            // EK material is OPTIONAL here (empty for the bounded-lab path); the verifier's strong-path policy
            // is what requires it — this mapper only decodes the shape.
            byte[] ekPub = decodeOptional(response.ekPubB64());
            byte[] ekCert = decodeOptional(response.ekCertB64());
            List<byte[]> ekCertChain = decodeOptionalList(response.ekCertChainB64());

            TpmDeviceKeySessionAttestation attestation = new TpmDeviceKeySessionAttestation(
                    response.challengeId(), response.schema(), deviceKeyPub, akPub, akName, ekPub, ekCert,
                    ekCertChain, certifyInfo, certifySig, quote, quoteSig, bindingContext, deviceKeySig,
                    response.signedAtEpochMillis());
            return attestation.hasRequiredCoreEvidence() ? Optional.of(attestation) : Optional.empty();
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static byte[] decodeRequired(String b64) {
        if (b64 == null || b64.isBlank()) {
            throw new IllegalArgumentException("missing required field");
        }
        byte[] decoded = Base64.getDecoder().decode(b64);
        if (decoded.length == 0) {
            throw new IllegalArgumentException("empty required field");
        }
        return decoded;
    }

    private static byte[] decodeOptional(String b64) {
        if (b64 == null || b64.isBlank()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(b64);
    }

    private static List<byte[]> decodeOptionalList(List<String> b64List) {
        if (b64List == null || b64List.isEmpty()) {
            return List.of();
        }
        List<byte[]> out = new ArrayList<>(b64List.size());
        for (String entry : b64List) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException("blank EK-chain entry");
            }
            out.add(Base64.getDecoder().decode(entry));
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
