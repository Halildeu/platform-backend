package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.agent.AutoEnrollmentRequest;
import com.example.endpointadmin.dto.v1.agent.AutoEnrollmentResponse;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointMachineCert;
import com.example.endpointadmin.model.MachineCertChannel;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.security.MachineCertExtractionException;
import com.example.endpointadmin.security.MachineCertExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.server.ResponseStatusException;

import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Faz 22.3 — Backend mTLS self-enrollment service. ADR-0029 backend layer.
 * Codex plan-time consult thread {@code 019e692b-f023-75a1-a2e9-a915f9cd58ee}.
 * Codex post-impl review {@code 019e6dc9} PARTIAL absorbed.
 *
 * <p>Flow per {@link #autoEnroll}:
 * <ol>
 *   <li>Parse the presented cert via {@link MachineCertExtractor}
 *       (validity + EKU + SAN URI exactly-one). Failure → 401 with stable
 *       error code.</li>
 *   <li>Idempotent lookup by SAN URI: if an active row exists for the same
 *       presented certificate, return it as {@code already-enrolled}
 *       (HTTP 200). If the SAN is the same but the certificate identity has
 *       changed, rotate the active cert row before returning HTTP 200 so
 *       downstream mTLS device binding uses the current thumbprint.
 *       Cross-tenant attempt detected here → 403.</li>
 *   <li>Otherwise: dedupe by {@code machineFingerprint} within the tenant. A
 *       conflicting active fingerprint → 409.</li>
 *   <li>Create a new {@link EndpointDevice} (or reuse an existing un-enrolled
 *       row keyed by tenant+hostname) and persist a new
 *       {@link EndpointMachineCert} active row. Audit
 *       {@code MACHINE_CERT_AUTO_ENROLL_SUCCESS}.</li>
 * </ol>
 *
 * <h2>Transactional model (Codex 019e6dc9 P0-2 absorb)</h2>
 *
 * <p>The service is {@code @Transactional(noRollbackFor =
 * ResponseStatusException.class)} so the failure-path
 * {@code MACHINE_CERT_AUTO_ENROLL_FAILED} audit row (recorded BEFORE the
 * {@code ResponseStatusException} is thrown) survives the throw and reaches
 * the audit hash-chain. Without this annotation Spring's default rollback
 * on runtime exceptions would erase forensic evidence of refused enrollments
 * (mirrors the pattern used in {@link EndpointMaintenanceTokenService}).
 *
 * <h2>Race handling (Codex 019e6dc9 P0-1 absorb)</h2>
 *
 * <p>The PostgreSQL transaction enters an aborted state after a constraint
 * violation, so an in-tx re-read after {@code DataIntegrityViolationException}
 * is unsafe. Both the device upsert (which can hit
     * {@code uq_endpoint_devices_tenant_fingerprint}) and the cert insert (which
     * can hit a channel-scoped SAN active index or
     * {@code uq_endpoint_machine_certs_device_channel_active}) catch
 * {@code DataIntegrityViolationException} and surface
 * {@code 409 ENROLLMENT_RACE} / {@code 409 DEVICE_RACE} respectively. The
 * caller retries — the second attempt reads the persisted winner via the
 * idempotent {@code findActiveBySanUri} pre-check and returns
 * {@code already-enrolled}. No in-tx re-read.
 */
@Service
public class MachineCertAutoEnrollService {

    private static final Logger log = LoggerFactory.getLogger(MachineCertAutoEnrollService.class);

    public static final String EVENT_SUCCESS = "MACHINE_CERT_AUTO_ENROLL_SUCCESS";
    public static final String EVENT_FAILED = "MACHINE_CERT_AUTO_ENROLL_FAILED";
    public static final String ACTION = "MACHINE_CERT_AUTO_ENROLL";

    private final EndpointMachineCertRepository certRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;

    public MachineCertAutoEnrollService(EndpointMachineCertRepository certRepository,
                                        EndpointDeviceRepository deviceRepository,
                                        EndpointAuditService auditService,
                                        Clock clock) {
        this.certRepository = certRepository;
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Self-enroll a machine identified by its presented mTLS client cert.
     *
     * <p>{@code noRollbackFor = ResponseStatusException.class} preserves the
     * audit row written on the failure path — see class javadoc.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Outcome autoEnroll(X509Certificate cert, UUID tenantId, AutoEnrollmentRequest request) {
        Instant now = Instant.now(clock);
        MachineCertExtractor.ParsedCert parsed;
        try {
            parsed = MachineCertExtractor.extract(cert, now);
        } catch (MachineCertExtractionException ex) {
            recordFailure(tenantId, null, ex.getErrorCode(),
                    Map.of(
                            "reason", ex.getErrorCode(),
                            "hostname", safe(request == null ? null : request.hostname())
                    ));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getErrorCode());
        }

        // Faz 22.6 #548 Phase 1.5 (G3) — reserve the tpm-/tpm: identity prefixes for the VAULT_TPM channel: an
        // AD_CS enrollment with a tpm- hostname or tpm:/tpm- fingerprint could squat a TPM device's synthetic
        // hostname/fingerprint slot or masquerade as a TPM identity. Reject fail-closed (case-insensitive).
        rejectReservedTpmPrefix(tenantId, parsed, request);

        // Idempotent SAN URI lookup.
        var existing = certRepository.findActiveBySanUri(parsed.sanUri());
        if (existing.isPresent()) {
            EndpointMachineCert active = existing.get();
            if (!active.getTenantId().equals(tenantId)) {
                recordFailure(tenantId, parsed, "TENANT_BOUNDARY",
                        Map.of(
                                "reason", "TENANT_BOUNDARY",
                                "sanUri", parsed.sanUri(),
                                "certTenantId", active.getTenantId().toString(),
                                "requestTenantId", tenantId.toString()
                        ));
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_BOUNDARY");
            }
            // Codex 019ea789 post-impl must-fix: the already-enrolled active-cert
            // path must ALSO reject a DECOMMISSIONED device — it returns early
            // without reaching adoptOrCreateDevice (where the no-revive guard
            // lives). Only an admin reactivate revives the device.
            if (active.getDevice() != null
                    && active.getDevice().getStatus() == DeviceStatus.DECOMMISSIONED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Endpoint device is decommissioned; admin reactivation is required before re-enrollment.");
            }
            EndpointMachineCert responseCert = rotateActiveCertIfNeeded(active, tenantId, parsed, request, now);
            String auditOutcome = Objects.equals(responseCert.getId(), active.getId())
                    ? "already-enrolled"
                    : "cert-rotated";
            recordSuccess(tenantId, responseCert.getDevice(), parsed, request, auditOutcome);
            return new Outcome(
                    HttpStatus.OK,
                    new AutoEnrollmentResponse(
                            responseCert.getDevice().getId(),
                            "already-enrolled",
                            responseCert.getEnrolledAt(),
                            new AutoEnrollmentResponse.CertInfo(
                                    parsed.sanUri(),
                                    parsed.objectGuid(),
                                    parsed.thumbprint(),
                                    parsed.notAfter()
                            )
                    )
            );
        }

        // Fingerprint dedupe (within tenant). Active-cert query.
        List<EndpointMachineCert> fpClashes = certRepository
                .findActiveByTenantAndMachineFingerprint(tenantId, request.machineFingerprint());
        if (!fpClashes.isEmpty()) {
            recordFailure(tenantId, parsed, "FINGERPRINT_CONFLICT",
                    Map.of(
                            "reason", "FINGERPRINT_CONFLICT",
                            "certThumbprint", parsed.thumbprint(),
                            "hostname", safe(request.hostname()),
                            "conflictingDeviceIds",
                            fpClashes.stream().map(c -> c.getDevice().getId().toString()).toList()
                    ));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FINGERPRINT_CONFLICT");
        }

        // Create / adopt device.
        EndpointDevice device;
        try {
            device = adoptOrCreateDevice(tenantId, request, now);
        } catch (DataIntegrityViolationException ex) {
            // Codex 019e6dc9 P1-6: device persistence race (concurrent enroll
            // hit uq_endpoint_devices_tenant_fingerprint). The PG tx is now
            // aborted; we surface 409 DEVICE_RACE rather than try to re-read.
            // The caller retries and the second attempt's pre-check resolves
            // it as already-enrolled.
            //
            // Codex 019e6dc9 iter-2 P1: explicit setRollbackOnly() so the
            // outer @Transactional(noRollbackFor = ResponseStatusException)
            // does NOT attempt a commit on a PG tx already in rollback-only
            // state — a commit attempt would mask the 409 with a generic
            // TransactionSystemException.
            log.info("Device persistence race tenant={}: {}", tenantId,
                    ex.getMostSpecificCause() == null ? ex.getMessage()
                            : ex.getMostSpecificCause().getMessage());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            // recordFailure inside the same tx is unreliable post-abort; we
            // skip it here. The retry path's success audit (or the next
            // failure's pre-abort audit) is the persisted trace.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DEVICE_RACE");
        }

        // Insert cert row. Codex 019e6dc9 P0-1: do NOT try a same-tx re-read
        // after DataIntegrityViolation — PG tx is aborted. Surface 409 and
        // let the caller retry; the retry's pre-check finds the winner.
        EndpointMachineCert certRow = buildCertRow(device, tenantId, parsed, request, now);

        try {
            certRepository.saveAndFlush(certRow);
        } catch (DataIntegrityViolationException ex) {
            // Codex 019e6dc9 iter-2 P1: same setRollbackOnly() rationale as
            // the device-race branch above — PG tx is aborted, mark
            // rollback-only so the outer commit attempt does not mask the
            // 409 ENROLLMENT_RACE with a TransactionSystemException.
            log.info("Cert insert race sanUri={}: {}", parsed.sanUri(),
                    ex.getMostSpecificCause() == null ? ex.getMessage()
                            : ex.getMostSpecificCause().getMessage());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ENROLLMENT_RACE");
        }

        recordSuccess(tenantId, device, parsed, request, "enrolled");
        return new Outcome(
                HttpStatus.CREATED,
                new AutoEnrollmentResponse(
                        device.getId(),
                        "enrolled",
                        now,
                        new AutoEnrollmentResponse.CertInfo(
                                parsed.sanUri(),
                                parsed.objectGuid(),
                                parsed.thumbprint(),
                                parsed.notAfter()
                        )
                )
        );
    }

    /**
     * Authenticate an already-enrolled mTLS machine cert for tokenless agent
     * lifecycle calls such as heartbeat. This deliberately returns the same
     * principal shape as the HMAC device credential path so downstream services
     * keep one persistence/audit behavior.
     */
    @Transactional(readOnly = true)
    public DeviceCredentialResult authenticateLifecycle(X509Certificate cert, UUID tenantId) {
        Instant now = Instant.now(clock);
        MachineCertExtractor.ParsedCert parsed;
        try {
            parsed = MachineCertExtractor.extract(cert, now);
        } catch (MachineCertExtractionException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getErrorCode());
        }

        EndpointMachineCert active = certRepository.findActiveBySanUri(parsed.sanUri())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "MACHINE_CERT_NOT_ENROLLED"));

        if (!active.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_BOUNDARY");
        }
        if (active.getDevice() == null || active.getDevice().getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "DEVICE_RE_ENROLL_REQUIRED");
        }
        if (active.getDevice().getStatus() == DeviceStatus.DECOMMISSIONED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Endpoint device is decommissioned; admin reactivation is required before lifecycle use.");
        }

        return new DeviceCredentialResult(
                active.getDevice().getId().toString(),
                "machine-cert:" + active.getId(),
                now
        );
    }

    private EndpointMachineCert rotateActiveCertIfNeeded(EndpointMachineCert active,
                                                          UUID tenantId,
                                                          MachineCertExtractor.ParsedCert parsed,
                                                          AutoEnrollmentRequest request,
                                                          Instant now) {
        if (samePresentedCertificate(active, parsed)) {
            return active;
        }

        rejectConflictingFingerprintForRotation(active, tenantId, parsed, request);

        active.setRevokedAt(now);
        active.setRevokedReason("CERT_ROTATED");

        EndpointMachineCert replacement = buildCertRow(active.getDevice(), tenantId, parsed, request, now);
        try {
            certRepository.saveAndFlush(active);
            return certRepository.saveAndFlush(replacement);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException ex) {
            log.info("Cert rotation race sanUri={}: {}", parsed.sanUri(), raceMessage(ex));
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ENROLLMENT_RACE");
        }
    }

    private void rejectConflictingFingerprintForRotation(EndpointMachineCert active,
                                                         UUID tenantId,
                                                         MachineCertExtractor.ParsedCert parsed,
                                                         AutoEnrollmentRequest request) {
        List<EndpointMachineCert> fpClashes = certRepository
                .findActiveByTenantAndMachineFingerprint(tenantId, request.machineFingerprint())
                .stream()
                .filter(c -> !Objects.equals(c.getId(), active.getId()))
                .toList();
        if (fpClashes.isEmpty()) {
            return;
        }

        recordFailure(tenantId, parsed, "FINGERPRINT_CONFLICT",
                Map.of(
                        "reason", "FINGERPRINT_CONFLICT",
                        "certThumbprint", parsed.thumbprint(),
                        "hostname", safe(request.hostname()),
                        "conflictingDeviceIds",
                        fpClashes.stream().map(c -> c.getDevice().getId().toString()).toList()
                ));
        throw new ResponseStatusException(HttpStatus.CONFLICT, "FINGERPRINT_CONFLICT");
    }

    private boolean samePresentedCertificate(EndpointMachineCert active,
                                              MachineCertExtractor.ParsedCert parsed) {
        return Objects.equals(active.getCertThumbprint(), parsed.thumbprint())
                && Objects.equals(active.getCertSerial(), parsed.serial())
                && Objects.equals(active.getCertNotBefore(), parsed.notBefore())
                && Objects.equals(active.getCertNotAfter(), parsed.notAfter())
                && Objects.equals(active.getCertIssuer(), parsed.issuer())
                && Objects.equals(active.getCertSubject(), parsed.subject());
    }

    private EndpointMachineCert buildCertRow(EndpointDevice device,
                                             UUID tenantId,
                                             MachineCertExtractor.ParsedCert parsed,
                                             AutoEnrollmentRequest request,
                                             Instant enrolledAt) {
        EndpointMachineCert certRow = new EndpointMachineCert();
        // Do NOT setId() — @GeneratedValue handles it; manual id makes
        // Hibernate treat the entity as detached with null version.
        certRow.setDevice(device);
        certRow.setTenantId(tenantId);
        certRow.setChannel(MachineCertChannel.AD_CS); // Faz 22.6 #548 Phase 1.5 — explicit channel (object_guid NOT NULL).
        certRow.setSanUri(parsed.sanUri());
        certRow.setObjectGuid(parsed.objectGuid());
        certRow.setCertSerial(parsed.serial());
        certRow.setCertThumbprint(parsed.thumbprint());
        certRow.setCertIssuer(parsed.issuer());
        certRow.setCertSubject(parsed.subject());
        certRow.setCertNotBefore(parsed.notBefore());
        certRow.setCertNotAfter(parsed.notAfter());
        certRow.setMachineFingerprint(request.machineFingerprint());
        certRow.setEnrolledAt(enrolledAt);
        return certRow;
    }

    private String raceMessage(Exception ex) {
        if (ex instanceof DataIntegrityViolationException dive && dive.getMostSpecificCause() != null) {
            return dive.getMostSpecificCause().getMessage();
        }
        return ex.getMessage();
    }

    private EndpointDevice adoptOrCreateDevice(UUID tenantId,
                                                AutoEnrollmentRequest request,
                                                Instant now) {
        // Faz 21.1 PR2b-iv.b2 (Codex 019e8d1d AGREE): canonical effective-org
        // adoption resolver. Fingerprint-first / hostname-fallback order
        // preserved exactly; both lookups now use parenthesized OR pattern
        // (canonical post-PR2b-ii rows + legacy NULL rows both reachable).
        EndpointDevice device = deviceRepository
                .findVisibleToOrgAndMachineFingerprint(tenantId, request.machineFingerprint())
                .or(() -> deviceRepository.findVisibleToOrgAndHostname(tenantId, request.hostname()))
                .orElseGet(EndpointDevice::new);

        // Codex 019ea789 must-fix: mTLS auto-enroll must NOT revive a
        // decommissioned device — this path authenticates by client cert, NOT
        // the HMAC credential, so the auth-layer gate does not cover it. A
        // matched DECOMMISSIONED identity is rejected (409); only an admin
        // reactivate revives it.
        if (device.getId() != null && device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Endpoint device is decommissioned; admin reactivation is required before re-enrollment.");
        }

        if (device.getId() == null) {
            // Faz 21.1 PR2b-iv.b2 must-fix (Codex 019e8d1d): canonical write
            // path service must set BOTH org_id + tenant_id (Option A inline
            // pattern, same as PR2b-ii EndpointDeviceService:39-40). Without
            // this explicit setOrgId, the row depended on V29 trigger silent
            // compensation — which Codex 019e8d12 explicitly disallows as
            // "canonical write path service sets both" iddiası için yeterli.
            device.setTenantId(tenantId);
            device.setOrgId(tenantId);
            device.setEnrolledAt(now);
        }
        device.setHostname(request.hostname());
        device.setOsType(parseOsType(request.osName()));
        device.setOsVersion(composeOsVersion(request.osVersion(), request.osBuild()));
        device.setAgentVersion(request.agentVersion());
        device.setMachineFingerprint(request.machineFingerprint());
        device.setDomainName(request.domain());
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastSeenAt(now);
        if (device.getEnrolledAt() == null) {
            device.setEnrolledAt(now);
        }
        return deviceRepository.saveAndFlush(device);
    }

    private OsType parseOsType(String osName) {
        if (osName == null || osName.isBlank()) {
            return OsType.UNKNOWN;
        }
        String normalized = osName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("win")) {
            return OsType.WINDOWS;
        }
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return OsType.MACOS;
        }
        if (normalized.contains("linux")) {
            return OsType.LINUX;
        }
        return OsType.UNKNOWN;
    }

    private String composeOsVersion(String osVersion, String osBuild) {
        if (osVersion == null || osVersion.isBlank()) {
            return osBuild;
        }
        if (osBuild == null || osBuild.isBlank()) {
            return osVersion;
        }
        return osVersion + " (build " + osBuild + ")";
    }

    private void recordSuccess(UUID tenantId,
                                EndpointDevice device,
                                MachineCertExtractor.ParsedCert parsed,
                                AutoEnrollmentRequest request,
                                String outcome) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sanUri", parsed.sanUri());
        metadata.put("objectGuid", parsed.objectGuid().toString());
        metadata.put("certThumbprint", parsed.thumbprint());
        metadata.put("certSerial", parsed.serial());
        metadata.put("certNotAfter", parsed.notAfter().toString());
        metadata.put("machineFingerprint", request.machineFingerprint());
        metadata.put("hostname", safe(request.hostname()));
        metadata.put("deviceId", device.getId() == null ? null : device.getId().toString());
        metadata.put("outcome", outcome);
        auditService.record(tenantId, device, null,
                EVENT_SUCCESS,
                ACTION,
                "machine-cert:" + parsed.sanUri(),
                null,
                metadata,
                null,
                null);
    }

    /**
     * Record a failure audit event. {@code @Transactional(noRollbackFor =
     * ResponseStatusException.class)} on the outer method preserves this row
     * across the throw. We catch and swallow any audit-side runtime error
     * here so the original 401/403/409 still reaches the caller — but the
     * happy path of audit persistence is what callers depend on for
     * forensic queries (Codex 019e6dc9 P0-2 absorb).
     */
    private void recordFailure(UUID tenantId,
                                MachineCertExtractor.ParsedCert parsed,
                                String reason,
                                Map<String, Object> extra) {
        try {
            Map<String, Object> metadata = new HashMap<>(extra);
            if (parsed != null) {
                metadata.putIfAbsent("sanUri", parsed.sanUri());
                metadata.putIfAbsent("certThumbprint", parsed.thumbprint());
            }
            metadata.putIfAbsent("reason", reason);
            auditService.record(tenantId, null, null,
                    EVENT_FAILED,
                    ACTION,
                    "machine-cert:" + (parsed == null ? "unparsed" : parsed.sanUri()),
                    null,
                    metadata,
                    null,
                    null);
        } catch (RuntimeException ignored) {
            log.warn("Failed to record audit for failure reason={} (continuing)", reason);
        }
    }

    private void rejectReservedTpmPrefix(UUID tenantId, MachineCertExtractor.ParsedCert parsed,
                                         AutoEnrollmentRequest request) {
        String hostname = request == null ? null : request.hostname();
        String fingerprint = request == null ? null : request.machineFingerprint();
        if (startsWithCi(hostname, "tpm-") || startsWithCi(fingerprint, "tpm:") || startsWithCi(fingerprint, "tpm-")) {
            recordFailure(tenantId, parsed, "RESERVED_TPM_PREFIX",
                    Map.of("reason", "RESERVED_TPM_PREFIX", "hostname", safe(hostname)));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RESERVED_TPM_PREFIX");
        }
    }

    private static boolean startsWithCi(String value, String prefix) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    public record Outcome(HttpStatus status, AutoEnrollmentResponse body) {
    }
}
