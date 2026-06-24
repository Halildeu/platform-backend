package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.model.EndpointTpmDeviceBinding;
import com.example.endpointadmin.remoteaccess.CertThumbprint;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.DeviceKeyChallenge;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TpmDeviceKeySessionEvidenceStore.StoredEvidence;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.repository.EndpointTpmDeviceBindingRepository;
import com.example.endpointadmin.tpmattest.TpmAttestationVerifier;
import com.example.endpointadmin.tpmattest.TpmEkChainValidator;
import com.example.endpointadmin.tpmattest.TpmPublicArea;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.6 #548 slice-1 step-5 (Codex {@code 019efada}) — the CANONICAL device-key SESSION verifier: the only
 * {@link SessionDeviceTrustVerifier} that returns {@link Basis#HARDWARE_KEY_ATTESTATION} on a REAL, live,
 * TPM-native challenge-response. It is the production hardware leg the factory reserved (#743) until it landed.
 *
 * <p><b>The binding the weaker paths cannot make (Codex, security-critical).</b> {@code device_key == mTLS leaf}
 * + AK {@code Certify} + {@code EK → pinned root} ALONE are INSUFFICIENT: a software AK, or a genuine EK cert
 * <em>borrowed from another TPM</em>, would pass — AK TPM-residency is only ever established by the AK&harr;EK
 * binding, which is proven at ENROLLMENT (V10 MakeCredential/ActivateCredential) and persisted as the
 * {@link EndpointTpmDeviceBinding}. This verifier RE-BINDS the live session to that persisted enrollment record:
 * the live AK Name + EK fingerprint + device-key SPKI must equal what enrollment proved co-resident in the same
 * TPM. So a forged/borrowed AK or EK fails the persisted match, and the device key is bound three ways at once
 * (attested == live mTLS leaf == persisted enrollment).
 *
 * <p><b>Verification order (every step fail-closed; total — never throws):</b>
 * <ol>
 *   <li>session present + canonical tenant/device + a transport peer key;</li>
 *   <li>fresh, un-expired device-key evidence for {@code (sessionId, transportPeerKey)} (the broker challenge
 *       window) — else deny;</li>
 *   <li>RE-DERIVE the canonical binding context from the BROKER's consumed challenge + the authenticated
 *       transport, and require the agent signed exactly THAT (constant-time) — the agent-supplied bytes are
 *       never trusted as the signed message;</li>
 *   <li>the device key signs that context (live possession, transport-bound);</li>
 *   <li>resolve the active enrolled connected peer for the tenant/device, and require it IS this session's
 *       authenticated peer (no borrowing another device's enrollment);</li>
 *   <li>TRIPLE-equality of the device-key SPKI: attested == the live mTLS leaf's SPKI == the persisted
 *       enrollment binding's {@code device_key_spki_sha256};</li>
 *   <li>the persisted enrollment binding's AK (Name + pub digest, and the AK is a proper restricted signing key)
 *       and EK (chains to a pinned manufacturer root + fingerprint) match the live evidence — re-establishing
 *       the V10 AK&harr;EK binding;</li>
 *   <li>AK {@code Certify} of the device key (V4, TPM-resident non-exportable leaf) + a fresh AK {@code Quote}
 *       over the broker nonce (V5, replay-bound);</li>
 *   <li>ONLY then {@link DeviceTrustDecision#hardwareKeyAttested()}.</li>
 * </ol>
 *
 * <p><b>Keystone (Q2, Codex-corrected):</b> {@code session.transportPeerKey()} is the mTLS leaf <em>certificate</em>
 * DER SHA-256 ({@code PeerIdentityInterceptor} fingerprint), NOT an SPKI. The device-key&harr;leaf invariant is
 * therefore checked against the leaf's SubjectPublicKeyInfo obtained via the resolver
 * ({@link PeerIdentity#chain()} {@code .get(0).getPublicKey()}), never by comparing to {@code transportPeerKey}.
 */
public final class DeviceKeyAttestationRealSessionDeviceTrustVerifier implements SessionDeviceTrustVerifier {

    /**
     * The canonical response schema this verifier accepts (FROZEN with the #741 wire contract; mirrors
     * {@code remote_bridge.proto} {@code DeviceKeyAttestationResponse.schema}). A version-confusion guard: a
     * response carrying any other schema is denied before any crypto runs.
     */
    public static final String RESPONSE_SCHEMA = "faz22.6.device-key-session.v1";

    private final TpmDeviceKeySessionEvidenceStore evidenceStore;
    private final ConnectedDeviceResolver resolver;
    private final EndpointTpmDeviceBindingRepository bindings;
    private final TpmEkChainValidator ekChainValidator;

    public DeviceKeyAttestationRealSessionDeviceTrustVerifier(TpmDeviceKeySessionEvidenceStore evidenceStore,
                                                              ConnectedDeviceResolver resolver,
                                                              EndpointTpmDeviceBindingRepository bindings,
                                                              TpmEkChainValidator ekChainValidator) {
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.ekChainValidator = Objects.requireNonNull(ekChainValidator, "ekChainValidator");
    }

    @Override
    public DeviceTrustDecision verify(RemoteBridgeSession session, PeerTrust peerTrust, long nowEpochMillis) {
        if (session == null) {
            return DeviceTrustDecision.deny("missing-session");
        }
        String peerKey = session.transportPeerKey();
        String tenantRaw = session.operatorTenantId();
        String deviceRaw = session.deviceId();
        if (isBlank(peerKey) || isBlank(tenantRaw) || isBlank(deviceRaw)) {
            return DeviceTrustDecision.deny("missing-session-identity");
        }
        UUID tenant = parseCanonicalUuid(tenantRaw);
        UUID device = parseCanonicalUuid(deviceRaw);
        if (tenant == null || device == null) {
            return DeviceTrustDecision.deny("non-canonical-tenant-or-device");
        }
        try {
            return verifyInternal(session, peerKey, tenant, device, nowEpochMillis);
        } catch (RuntimeException totalFailClosed) {
            // total + fail-closed: any unexpected parse/crypto/repository error is never device trust, never a throw
            return DeviceTrustDecision.deny("device-key-attestation-error");
        }
    }

    private DeviceTrustDecision verifyInternal(RemoteBridgeSession session, String peerKey, UUID tenant,
                                               UUID device, long now) {
        // (2) fresh, un-expired device-key evidence for THIS session + peer (the broker challenge window)
        Optional<StoredEvidence> freshEvidence =
                evidenceStore.consumeFresh(session.sessionId(), peerKey, now);
        if (freshEvidence.isEmpty()) {
            return DeviceTrustDecision.deny("no-fresh-device-key-evidence");
        }
        StoredEvidence evidence = freshEvidence.get();
        DeviceKeyChallenge challenge = evidence.consumedChallenge();
        TpmDeviceKeySessionAttestation attestation = evidence.attestation();

        // version-confusion guard (Codex F2): pin BOTH the broker challenge protocol AND the response schema to
        // the frozen #548 literals before any crypto — a mismatched version is denied, never silently accepted.
        if (!DeviceKeyChallengeStore.PROTOCOL_VERSION.equals(challenge.protocolVersion())) {
            return DeviceTrustDecision.deny("challenge-protocol-mismatch");
        }
        if (!RESPONSE_SCHEMA.equals(attestation.schema())) {
            return DeviceTrustDecision.deny("response-schema-mismatch");
        }
        // the challenge MUST have been issued to THIS authenticated peer (it was, by construction — defense-in-depth)
        if (!CertThumbprint.matches(challenge.transportPeerKey(), peerKey)) {
            return DeviceTrustDecision.deny("challenge-peer-mismatch");
        }
        byte[] nonce = decodeBase64OrNull(challenge.nonceB64());
        if (nonce == null || nonce.length == 0) {
            return DeviceTrustDecision.deny("challenge-nonce-malformed");
        }

        // (3) RE-DERIVE the canonical binding context from BROKER truth (incl. THIS session id, Codex F1); require
        // the agent signed exactly THAT — so a signature is bound to this session + challenge + nonce + peer.
        byte[] expectedContext = DeviceKeySessionBindingContext.compute(
                session.sessionId(), challenge.challengeId(), nonce, peerKey, challenge.expiresAtEpochMillis());
        if (!MessageDigest.isEqual(expectedContext, attestation.bindingContext())) {
            return DeviceTrustDecision.deny("binding-context-mismatch");
        }

        // parse the TPM public areas (TPM2B_PUBLIC — the wire DTO carries the size-prefixed form, as at enrollment)
        TpmPublicArea deviceKey = TpmPublicArea.parse(attestation.deviceKeyPub(), true);
        TpmPublicArea ak = TpmPublicArea.parse(attestation.akPub(), true);

        // (4) the DEVICE KEY itself signs the binding context → live possession, transport-bound (throws → caught)
        TpmAttestationVerifier.verifyDeviceKeySignature(deviceKey, expectedContext, attestation.deviceKeySig());

        // (5) the active enrolled, in-window, connected peer for this tenant/device — and it IS this session's peer
        Optional<PeerIdentity> connected = resolver.resolveConnectedPeer(tenant, device, Instant.ofEpochMilli(now));
        if (connected.isEmpty()) {
            return DeviceTrustDecision.deny("no-active-enrolled-connected-peer");
        }
        PeerIdentity resolved = connected.get();
        if (!CertThumbprint.matches(resolved.transportPeerKey(), peerKey)) {
            return DeviceTrustDecision.deny("transport-peer-mismatch");
        }
        if (resolved.chain().isEmpty()) {
            return DeviceTrustDecision.deny("no-leaf-certificate");
        }
        X509Certificate leaf = resolved.chain().get(0);

        // the persisted, V10-proven enrollment TPM binding for this device — the AK<->EK anchor
        EndpointTpmDeviceBinding binding =
                bindings.findByTenantIdAndDeviceIdAndRevokedAtIsNull(tenant, device).orElse(null);
        if (binding == null) {
            return DeviceTrustDecision.deny("no-active-tpm-binding");
        }

        // (6) TRIPLE device-key SPKI equality: attested == live mTLS leaf == persisted enrollment binding
        String attestedSpki = CertThumbprint.ofDer(deviceKey.toPublicKey().getEncoded());
        String leafSpki = CertThumbprint.ofDer(leaf.getPublicKey().getEncoded());
        if (attestedSpki == null || leafSpki == null
                || !CertThumbprint.matches(attestedSpki, leafSpki)
                || !CertThumbprint.matches(attestedSpki, binding.getDeviceKeySpkiSha256())) {
            return DeviceTrustDecision.deny("device-key-leaf-binding-mismatch");
        }

        // (7) AK identity == the persisted enrollment AK (Name is the canonical compare; pub digest defense-in-depth)
        if (!ak.isRestrictedSigningKey()) {
            return DeviceTrustDecision.deny("ak-not-restricted-signing-key");
        }
        byte[] recomputedAkName = ak.computeName();
        if (!MessageDigest.isEqual(recomputedAkName, attestation.akName())
                || !MessageDigest.isEqual(recomputedAkName, nullToEmpty(binding.getAkName()))) {
            return DeviceTrustDecision.deny("ak-name-mismatch");
        }
        if (!CertThumbprint.matches(CertThumbprint.ofDer(attestation.akPub()), binding.getAkPubSha256())) {
            return DeviceTrustDecision.deny("ak-pub-mismatch");
        }

        // EK == the persisted enrollment EK + chains to a pinned manufacturer root (strong path requires the cert)
        byte[] ekCertDer = attestation.ekCert();
        if (ekCertDer.length == 0) {
            return DeviceTrustDecision.deny("ek-cert-required");
        }
        if (!CertThumbprint.matches(CertThumbprint.ofDer(ekCertDer), binding.getEkCertSha256())) {
            return DeviceTrustDecision.deny("ek-cert-mismatch");
        }
        if (!ekChainValidatesToPinnedRoot(ekCertDer, attestation.ekCertChain())) {
            return DeviceTrustDecision.deny("ek-chain-untrusted");
        }

        // (8) AK Certify of the device key (V4) + a fresh AK Quote over the broker nonce (V5) — throws → caught
        TpmAttestationVerifier.verifyCertify(ak, attestation.certifyInfo(), attestation.certifySig(), deviceKey);
        TpmAttestationVerifier.verifyQuote(ak, attestation.quote(), attestation.quoteSig(), nonce);

        // (9) every gate passed — REAL TPM-native hardware key attestation, live + transport-bound + enrollment-anchored
        return DeviceTrustDecision.hardwareKeyAttested();
    }

    private boolean ekChainValidatesToPinnedRoot(byte[] ekCertDer, List<byte[]> ekChainDer) {
        try {
            X509Certificate ekCert = TpmEkChainValidator.parseCert(ekCertDer);
            List<X509Certificate> intermediates = new ArrayList<>(ekChainDer.size());
            for (byte[] der : ekChainDer) {
                if (der != null && der.length > 0) {
                    intermediates.add(TpmEkChainValidator.parseCert(der));
                }
            }
            ekChainValidator.validate(ekCert, intermediates);
            return true;
        } catch (Exception untrusted) {
            return false; // malformed cert OR does not chain to a pinned root — fail-closed
        }
    }

    private static byte[] decodeBase64OrNull(String b64) {
        if (b64 == null || b64.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static byte[] nullToEmpty(byte[] value) {
        return value == null ? new byte[0] : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Strict canonical-UUID parse ({@code UUID.fromString} is lenient on case/spelling); non-canonical → null. */
    private static UUID parseCanonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
