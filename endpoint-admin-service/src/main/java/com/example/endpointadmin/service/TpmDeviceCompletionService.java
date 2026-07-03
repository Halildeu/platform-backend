package com.example.endpointadmin.service;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EndpointMachineCert;
import com.example.endpointadmin.model.EndpointTpmDeviceIdentity;
import com.example.endpointadmin.model.MachineCertChannel;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import com.example.endpointadmin.repository.EndpointTpmDeviceIdentityRepository;
import com.example.endpointadmin.security.TpmVaultCertExtractor;
import com.example.endpointadmin.tpmattest.TpmAttestException;
import com.example.endpointadmin.tpmattest.TpmDenyCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.6 #548 Phase 1.5 — TPM-native device completion (Codex {@code 019eff93} AGREE).
 *
 * <p>On a successful {@code /attest}, completes the canonical device so the device-key SESSION verifier
 * (DEVICE_KEY_ATTESTATION_REAL, later slice) — which keys on {@code (tenant, device)} — can match. Both
 * a device-less (system-native issuance) and a pre-bound enrollment go through this ONE hardened path:
 *
 * <ol>
 *   <li><b>Identity cross-check (P0-2):</b> the freshly Vault-issued cert's SAN {@code tpm:{ek_pub_sha256}}
 *       MUST equal the L1-bound, server-derived EK identity. The session verifier authenticates THIS cert by
 *       that SAN, so a mismatch would mint a permanently-unauthenticatable device — fail closed instead.</li>
 *   <li><b>Device resolution (P0-3/P0-4):</b> the SOLE adoption authority is the tenant-scoped
 *       {@link EndpointTpmDeviceIdentity} map ({@code (tenant_id, ek_pub_sha256)}), NEVER the agent-supplied
 *       {@code machine_fingerprint}. Pre-bound: assert the target device's tenant + non-decommissioned, and
 *       refuse if the EK is already bound to a DIFFERENT device (anti-hijack). Device-less: reuse the mapped
 *       device or create a new one (synthetic {@code tpm-{ek}} hostname) + identity row.</li>
 *   <li><b>Cert register/rotate (Inv-1/P1-6):</b> revoke the device's current ACTIVE VAULT_TPM cert only,
 *       preserving any active AD_CS product-channel credential, BEFORE inserting the new VAULT_TPM row.</li>
 *   <li>Link {@code enrollment.device} to the resolved device; the caller then writes the binding row.</li>
 * </ol>
 *
 * <p><b>{@code @Transactional(MANDATORY)}</b> — runs ONLY inside the caller's ({@code markConsumed}) transaction,
 * so the device + cert + identity + enrollment-link + binding are one atomic unit; a misconfigured direct call
 * fails loudly rather than silently opening a non-atomic tx. Any constraint race fails closed (the tx rolls
 * back, the controller marks the enrollment TPM_FAILED, uniform 403) — the issued Vault cert is then orphaned
 * (operational waste, not a trust gap: with no cert row + binding the session verifier denies).
 */
@Service
public class TpmDeviceCompletionService {

    private static final Logger log = LoggerFactory.getLogger(TpmDeviceCompletionService.class);

    public static final String EVENT_SUCCESS = "TPM_DEVICE_COMPLETION_SUCCESS";
    public static final String ACTION = "TPM_DEVICE_COMPLETION";

    private final EndpointDeviceRepository deviceRepository;
    private final EndpointMachineCertRepository certRepository;
    private final EndpointTpmDeviceIdentityRepository identityRepository;
    private final EndpointAuditService auditService;

    public TpmDeviceCompletionService(EndpointDeviceRepository deviceRepository,
                                      EndpointMachineCertRepository certRepository,
                                      EndpointTpmDeviceIdentityRepository identityRepository,
                                      EndpointAuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.certRepository = certRepository;
        this.identityRepository = identityRepository;
        this.auditService = auditService;
    }

    /**
     * Complete the device for a successful {@code /attest}. Returns the canonical device id (never null).
     *
     * @param tenantId     the enrollment tenant (server-derived scope)
     * @param ekPubSha256  the L1-bound EK public-key digest (lowercase 64-hex; the canonical TPM identity)
     * @param vaultCert    the parsed freshly-issued Vault cert (its SAN is cross-checked against {@code ekPubSha256})
     * @param enrollment   the enrollment being consumed (its {@code device} is linked to the resolved device)
     * @param scopeDeviceId the pre-bound target device id, or {@code null} for a device-less (system-native) enrollment
     * @param now          the completion instant
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID complete(UUID tenantId, String ekPubSha256, TpmVaultCertExtractor.ParsedVaultCert vaultCert,
                         EndpointEnrollment enrollment, UUID scopeDeviceId, Instant now) {
        // P0-2: the issued cert's SAN MUST equal the L1-bound EK identity (server-supplied uri_sans round-trip).
        if (vaultCert == null || ekPubSha256 == null
                || !ekPubSha256.equals(vaultCert.ekPubSha256())) {
            throw deny(TpmDenyCode.EK_UNTRUSTED, "issued Vault cert SAN != L1-bound EK identity");
        }
        String sanUri = "tpm:" + ekPubSha256;

        try {
            EndpointDevice device = resolveDevice(tenantId, ekPubSha256, scopeDeviceId, now);
            registerVaultTpmCert(device, tenantId, sanUri, ekPubSha256, vaultCert, now);
            enrollment.setDevice(device); // no-op when already the pre-bound target
            recordSuccess(tenantId, device, ekPubSha256, sanUri, vaultCert, scopeDeviceId != null);
            return device.getId();
        } catch (DataIntegrityViolationException race) {
            // Concurrent completion of the same EK (device / identity / cert partial-unique). The nonce is
            // already consumed, so there is no safe retry on THIS request — fail closed (uniform 403). The PG
            // tx is now aborted; the outer markConsumed @Transactional rolls everything back.
            log.info("TPM device-completion race tenant={} ek={}: {}", tenantId, shortEk(ekPubSha256),
                    race.getMostSpecificCause() == null ? race.getMessage()
                            : race.getMostSpecificCause().getMessage());
            throw deny(TpmDenyCode.DEVICE_NOT_ELIGIBLE, "device-completion race");
        }
    }

    private EndpointDevice resolveDevice(UUID tenantId, String ekPubSha256, UUID scopeDeviceId, Instant now) {
        Optional<EndpointTpmDeviceIdentity> identityOpt =
                identityRepository.findByTenantIdAndEkPubSha256(tenantId, ekPubSha256);

        if (scopeDeviceId != null) {
            // Pre-bound: the enrollment targets an existing device (e.g. an AD_CS device upgrading to TPM-native).
            EndpointDevice target = deviceRepository.findById(scopeDeviceId)
                    .orElseThrow(() -> deny(TpmDenyCode.DEVICE_NOT_ELIGIBLE, "pre-bound enrollment device absent"));
            requireSameTenant(target, tenantId);
            requireNotDecommissioned(target);
            if (identityOpt.isPresent()) {
                EndpointTpmDeviceIdentity identity = identityOpt.get();
                requireIdentityTenant(identity, tenantId);
                if (!identity.getDeviceId().equals(target.getId())) {
                    // Anti-hijack: this EK already maps to a different device in the tenant.
                    throw deny(TpmDenyCode.DEVICE_NOT_ELIGIBLE, "EK identity already bound to a different device");
                }
            } else {
                identityRepository.saveAndFlush(
                        new EndpointTpmDeviceIdentity(tenantId, ekPubSha256, target.getId(), now));
            }
            return target;
        }

        // Device-less: adopt via the canonical identity map; never via agent-supplied fingerprint.
        if (identityOpt.isPresent()) {
            EndpointTpmDeviceIdentity identity = identityOpt.get();
            requireIdentityTenant(identity, tenantId);
            EndpointDevice device = deviceRepository.findById(identity.getDeviceId())
                    .orElseThrow(() -> deny(TpmDenyCode.DEVICE_NOT_ELIGIBLE, "TPM identity device absent"));
            // device_id is a single-column FK; a raced/corrupt row could pair tenants — assert (G2).
            requireSameTenant(device, tenantId);
            requireNotDecommissioned(device);
            return device;
        }

        // First enrollment of this EK in this tenant — create the canonical device + its identity row.
        EndpointDevice device = createTpmDevice(tenantId, ekPubSha256, now);
        identityRepository.saveAndFlush(
                new EndpointTpmDeviceIdentity(tenantId, ekPubSha256, device.getId(), now));
        return device;
    }

    private EndpointDevice createTpmDevice(UUID tenantId, String ekPubSha256, Instant now) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        device.setOrgId(tenantId); // Faz 21.1 canonical write path sets BOTH org_id + tenant_id.
        // Synthetic, deterministic, tenant-unique identity for the NOT-NULL+UNIQUE device columns. The
        // machine_fingerprint sentinel is NEVER an adoption key (adoption is the identity table); the AD_CS
        // service rejects any agent hostname/fingerprint with a tpm-/tpm: prefix, so it can never squat this.
        device.setHostname("tpm-" + ekPubSha256);
        device.setMachineFingerprint("tpm:" + ekPubSha256);
        device.setOsType(OsType.UNKNOWN); // the /attest envelope carries no host metadata.
        device.setStatus(DeviceStatus.ONLINE);
        device.setEnrolledAt(now);
        device.setLastSeenAt(now);
        return deviceRepository.saveAndFlush(device); // surface the device unique-constraint race immediately.
    }

    private void registerVaultTpmCert(EndpointDevice device, UUID tenantId, String sanUri, String ekPubSha256,
                                      TpmVaultCertExtractor.ParsedVaultCert vaultCert, Instant now) {
        // Inv-1 + P1-6: TPM rotation supersedes only the VAULT_TPM slot. AD_CS stays active for product
        // remote-bridge/lifecycle until its own AD_CS renewal/revocation path changes it.
        certRepository.revokeActiveCertForDeviceAndChannel(
                device.getId(), MachineCertChannel.VAULT_TPM, now, "TPM_NATIVE_SUPERSEDED");

        EndpointMachineCert certRow = new EndpointMachineCert();
        certRow.setDevice(device);
        certRow.setTenantId(tenantId);
        certRow.setChannel(MachineCertChannel.VAULT_TPM);
        certRow.setSanUri(sanUri);
        certRow.setObjectGuid(null); // VAULT_TPM channel — no AD objectGUID (CHECK enforces NULL).
        certRow.setCertSerial(vaultCert.serial());
        certRow.setCertThumbprint(vaultCert.thumbprint());
        certRow.setCertIssuer(vaultCert.issuer());
        certRow.setCertSubject(vaultCert.subject());
        certRow.setCertNotBefore(vaultCert.notBefore());
        certRow.setCertNotAfter(vaultCert.notAfter());
        certRow.setMachineFingerprint("tpm:" + ekPubSha256); // sentinel (NOT-NULL col); never adoption authority.
        certRow.setEnrolledAt(now);
        certRepository.saveAndFlush(certRow);
    }

    private void requireSameTenant(EndpointDevice device, UUID tenantId) {
        if (!tenantId.equals(device.getTenantId())) {
            throw deny(TpmDenyCode.DEVICE_NOT_ELIGIBLE, "device tenant mismatch");
        }
    }

    private void requireIdentityTenant(EndpointTpmDeviceIdentity identity, UUID tenantId) {
        if (!tenantId.equals(identity.getTenantId())) {
            throw deny(TpmDenyCode.DEVICE_NOT_ELIGIBLE, "TPM identity tenant mismatch");
        }
    }

    private void requireNotDecommissioned(EndpointDevice device) {
        if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            // No-revive (mirror MachineCertAutoEnrollService): only an admin reactivate revives a device.
            throw deny(TpmDenyCode.DEVICE_NOT_ELIGIBLE, "device is decommissioned; admin reactivation required");
        }
    }

    private void recordSuccess(UUID tenantId, EndpointDevice device, String ekPubSha256, String sanUri,
                               TpmVaultCertExtractor.ParsedVaultCert vaultCert, boolean preBound) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("channel", MachineCertChannel.VAULT_TPM.name());
        metadata.put("ekPubSha256", ekPubSha256);
        metadata.put("sanUri", sanUri);
        metadata.put("certThumbprint", vaultCert.thumbprint());
        metadata.put("certSerial", vaultCert.serial());
        metadata.put("certNotAfter", vaultCert.notAfter().toString());
        metadata.put("deviceId", device.getId() == null ? null : device.getId().toString());
        metadata.put("preBound", preBound);
        auditService.record(tenantId, device, null, EVENT_SUCCESS, ACTION,
                "machine-cert:" + sanUri, null, metadata, null, null);
    }

    private static String shortEk(String ekPubSha256) {
        return ekPubSha256 == null ? "null" : ekPubSha256.substring(0, Math.min(16, ekPubSha256.length()));
    }

    private static TpmAttestException deny(TpmDenyCode code, String detail) {
        return new TpmAttestException(code, detail);
    }
}
