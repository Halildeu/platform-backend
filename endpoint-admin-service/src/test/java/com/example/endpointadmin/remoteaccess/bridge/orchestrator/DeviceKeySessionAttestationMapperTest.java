package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 #548 slice-1 step-2 — {@link DeviceKeySessionAttestationMapper} decodes a wire-validated
 * {@code DeviceKeyAttestationResponse} into the TPM-native {@link TpmDeviceKeySessionAttestation} shape, fail-closed.
 */
class DeviceKeySessionAttestationMapperTest {

    // distinct valid base64 per field, so a swap/cross-wiring would fail assertArrayEquals
    private static final String DK = "ZGV2aWNlS2V5";       // deviceKey
    private static final String AKP = "YWtQdWI=";          // akPub
    private static final String AKN = "YWtOYW1l";          // akName
    private static final String EKP = "ZWtQdWI=";          // ekPub
    private static final String EKC = "ZWtDZXJ0";          // ekCert
    private static final List<String> CHAIN = List.of("Y2hhaW4x", "Y2hhaW4y"); // chain1, chain2
    private static final String CI = "Y2VydEluZm8=";       // certInfo
    private static final String CS = "Y2VydFNpZw==";       // certSig
    private static final String Q = "cXVvdGU=";            // quote
    private static final String QS = "cXVvdGVTaWc=";       // quoteSig
    private static final String BC = "YmluZGluZw==";       // binding
    private static final String DKS = "ZGtTaWc=";          // dkSig
    private static final String CHAL = "chal-1";
    private static final String SCHEMA = "faz22.6.device-key-session.v1";

    private static RemoteBridgeMessages.DeviceKeyAttestationResponse resp(
            String challengeId, String schema, String dk, String akp, String akn, String ekp, String ekc,
            List<String> chain, String ci, String cs, String q, String qs, String bc, String dks) {
        return new RemoteBridgeMessages.DeviceKeyAttestationResponse(
                challengeId, schema, dk, akp, akn, ekp, ekc, chain, ci, cs, q, qs, bc, dks, 1000L);
    }

    private static byte[] dec(String b64) {
        return Base64.getDecoder().decode(b64);
    }

    @Test
    void mapsAFullyPopulatedResponseWithoutCrossWiringTheFields() {
        Optional<TpmDeviceKeySessionAttestation> mapped = DeviceKeySessionAttestationMapper.map(
                resp(CHAL, SCHEMA, DK, AKP, AKN, EKP, EKC, CHAIN, CI, CS, Q, QS, BC, DKS));
        assertTrue(mapped.isPresent(), "a complete response maps");
        TpmDeviceKeySessionAttestation a = mapped.get();
        assertEquals(CHAL, a.challengeId());
        assertEquals(SCHEMA, a.schema());
        assertEquals(1000L, a.signedAtEpochMillis());
        assertArrayEquals(dec(DK), a.deviceKeyPub());
        assertArrayEquals(dec(AKP), a.akPub());
        assertArrayEquals(dec(AKN), a.akName());
        assertArrayEquals(dec(EKP), a.ekPub());
        assertArrayEquals(dec(EKC), a.ekCert());
        assertEquals(2, a.ekCertChain().size());
        assertArrayEquals(dec("Y2hhaW4x"), a.ekCertChain().get(0));
        assertArrayEquals(dec("Y2hhaW4y"), a.ekCertChain().get(1));
        assertArrayEquals(dec(CI), a.certifyInfo());
        assertArrayEquals(dec(CS), a.certifySig());
        assertArrayEquals(dec(Q), a.quote());
        assertArrayEquals(dec(QS), a.quoteSig());
        assertArrayEquals(dec(BC), a.bindingContext());
        assertArrayEquals(dec(DKS), a.deviceKeySig());
        assertTrue(a.hasRequiredCoreEvidence());
    }

    @Test
    void mapsTheBoundedLabPathWithEmptyEkMaterial() {
        Optional<TpmDeviceKeySessionAttestation> mapped = DeviceKeySessionAttestationMapper.map(
                resp(CHAL, SCHEMA, DK, AKP, AKN, "", "", List.of(), CI, CS, Q, QS, BC, DKS));
        assertTrue(mapped.isPresent(), "EK material is optional — the bounded-lab path still maps");
        TpmDeviceKeySessionAttestation a = mapped.get();
        assertEquals(0, a.ekPub().length);
        assertEquals(0, a.ekCert().length);
        assertTrue(a.ekCertChain().isEmpty());
        assertTrue(a.hasRequiredCoreEvidence(), "required core evidence does not include EK material");
    }

    @Test
    void rejectsNull() {
        assertTrue(DeviceKeySessionAttestationMapper.map(null).isEmpty());
    }

    @Test
    void rejectsABlankRequiredField() {
        // certifyInfo is a required core member — a blank one denies (fail-closed)
        assertTrue(DeviceKeySessionAttestationMapper.map(
                resp(CHAL, SCHEMA, DK, AKP, AKN, EKP, EKC, CHAIN, "", CS, Q, QS, BC, DKS)).isEmpty());
    }

    @Test
    void rejectsAMalformedRequiredBase64() {
        assertTrue(DeviceKeySessionAttestationMapper.map(
                resp(CHAL, SCHEMA, DK, AKP, AKN, EKP, EKC, CHAIN, CI, CS, "@@@@", QS, BC, DKS)).isEmpty());
    }

    @Test
    void rejectsAMalformedOptionalEkField() {
        assertTrue(DeviceKeySessionAttestationMapper.map(
                resp(CHAL, SCHEMA, DK, AKP, AKN, "@@@@", EKC, CHAIN, CI, CS, Q, QS, BC, DKS)).isEmpty());
    }

    @Test
    void rejectsABlankEkChainEntry() {
        assertTrue(DeviceKeySessionAttestationMapper.map(
                resp(CHAL, SCHEMA, DK, AKP, AKN, EKP, EKC, List.of("Y2hhaW4x", ""), CI, CS, Q, QS, BC, DKS))
                .isEmpty());
    }

    @Test
    void rejectsABlankChallengeIdOrSchema() {
        assertTrue(DeviceKeySessionAttestationMapper.map(
                resp("", SCHEMA, DK, AKP, AKN, EKP, EKC, CHAIN, CI, CS, Q, QS, BC, DKS)).isEmpty());
        assertTrue(DeviceKeySessionAttestationMapper.map(
                resp(CHAL, "  ", DK, AKP, AKN, EKP, EKC, CHAIN, CI, CS, Q, QS, BC, DKS)).isEmpty());
    }

    @Test
    void hasRequiredCoreEvidenceIsFalseWhenAnArrayMemberIsEmpty() {
        // a record built directly with an empty required array is not core-complete
        TpmDeviceKeySessionAttestation a = new TpmDeviceKeySessionAttestation(
                CHAL, SCHEMA, new byte[0], dec(AKP), dec(AKN), new byte[0], new byte[0], List.of(),
                dec(CI), dec(CS), dec(Q), dec(QS), dec(BC), dec(DKS), 1000L);
        assertFalse(a.hasRequiredCoreEvidence(), "empty deviceKeyPub fails the core-evidence shape check");
    }

    @Test
    void constructorClonesInputArraysSoCallerMutationDoesNotLeakIn() {
        byte[] dk = dec(DK);
        byte[] chainEntry = dec("Y2hhaW4x");
        TpmDeviceKeySessionAttestation a = new TpmDeviceKeySessionAttestation(
                CHAL, SCHEMA, dk, dec(AKP), dec(AKN), new byte[0], new byte[0], List.of(chainEntry),
                dec(CI), dec(CS), dec(Q), dec(QS), dec(BC), dec(DKS), 1000L);
        dk[0] = (byte) 0xFF;          // mutate the caller's array AFTER construction
        chainEntry[0] = (byte) 0xFF;  // and a chain entry the caller still holds
        assertArrayEquals(dec(DK), a.deviceKeyPub(), "ctor must clone scalar arrays");
        assertArrayEquals(dec("Y2hhaW4x"), a.ekCertChain().get(0), "ctor must deep-copy the EK chain");
    }

    @Test
    void accessorsReturnClonesSoMutatingThemDoesNotAffectTheRecord() {
        TpmDeviceKeySessionAttestation a = new TpmDeviceKeySessionAttestation(
                CHAL, SCHEMA, dec(DK), dec(AKP), dec(AKN), new byte[0], new byte[0], List.of(dec("Y2hhaW4x")),
                dec(CI), dec(CS), dec(Q), dec(QS), dec(BC), dec(DKS), 1000L);
        a.deviceKeyPub()[0] = (byte) 0xFF;        // mutate the returned scalar clone
        a.ekCertChain().get(0)[0] = (byte) 0xFF;  // mutate a returned chain-entry clone
        assertArrayEquals(dec(DK), a.deviceKeyPub(), "scalar accessor returns a fresh clone each call");
        assertArrayEquals(dec("Y2hhaW4x"), a.ekCertChain().get(0), "chain accessor returns deep clones each call");
    }
}
