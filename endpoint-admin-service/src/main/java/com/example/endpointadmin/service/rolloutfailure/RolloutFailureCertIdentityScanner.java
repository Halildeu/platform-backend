package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EndpointMachineCert;
import com.example.endpointadmin.model.EnrollmentStatus;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.repository.EndpointEnrollmentRepository;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * #527 CERT_IDENTITY autonomous ingest from backend-owned certificate and
 * enrollment state.
 *
 * <p>This scanner deliberately uses structured sources the backend already owns:
 * active machine-cert validity windows and device-bound TPM enrollment failures.
 * It does not infer DNS/EDR causes from generic timeouts, and it does not write
 * raw issuer/SAN/cert material into evidence. Every emitted object is validated
 * by the same queue evidence allowlist as manual seed and command-result ingest.
 */
@Component
@ConditionalOnPrimaryEndpointPlane
@ConditionalOnProperty(
        prefix = "endpoint-admin.rollout-failure.cert-identity",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RolloutFailureCertIdentityScanner {

    private static final Logger log = LoggerFactory.getLogger(RolloutFailureCertIdentityScanner.class);

    static final String CLASSIFIER_VERSION = "auto:cert-identity:v1";
    static final String WAVE_ID = RolloutFailureClass.CERT_IDENTITY.name();
    static final String ROLLOUT_ID_ACTIVE_CERT_EXPIRED = "cert-identity-auto:active-cert-expired";
    static final String ROLLOUT_ID_TPM_ENROLLMENT_FAILED = "cert-identity-auto:tpm-enrollment-failed";

    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{8,}$");
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private final EndpointMachineCertRepository certRepository;
    private final EndpointEnrollmentRepository enrollmentRepository;
    private final RolloutFailureAutoIngestService autoIngestService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int batchSize;

    public RolloutFailureCertIdentityScanner(
            EndpointMachineCertRepository certRepository,
            EndpointEnrollmentRepository enrollmentRepository,
            RolloutFailureAutoIngestService autoIngestService,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${endpoint-admin.rollout-failure.cert-identity.batch-size:100}")
            int batchSize) {
        this.certRepository = certRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.autoIngestService = autoIngestService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(
            fixedDelayString = "${endpoint-admin.rollout-failure.cert-identity.scan-interval-ms:900000}",
            initialDelayString = "${endpoint-admin.rollout-failure.cert-identity.initial-delay-ms:360000}")
    public int scanCertIdentityFailures() {
        Instant now = Instant.now(clock);
        int ingested = 0;
        for (EndpointMachineCert cert : certRepository.findExpiredActiveCerts(
                now, DeviceStatus.DECOMMISSIONED, PageRequest.of(0, batchSize))) {
            if (ingestExpiredActiveCert(cert, now)) {
                ingested++;
            }
        }
        for (EndpointEnrollment enrollment : enrollmentRepository.findDeviceBoundByStatusExcludingDeviceStatus(
                EnrollmentStatus.TPM_FAILED, DeviceStatus.DECOMMISSIONED, PageRequest.of(0, batchSize))) {
            if (ingestFailedTpmEnrollment(enrollment, now)) {
                ingested++;
            }
        }
        return ingested;
    }

    boolean ingestExpiredActiveCert(EndpointMachineCert cert, Instant now) {
        if (cert == null || cert.getId() == null || cert.getDevice() == null
                || cert.getCertNotAfter() == null || cert.getCertNotAfter().isAfter(now)) {
            return false;
        }
        EndpointDevice device = cert.getDevice();
        UUID tenantId = matchingTenantId(device, cert.getTenantId(), "machine-cert", cert.getId());
        if (tenantId == null || device.getId() == null || device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            return false;
        }

        RolloutFailureClassifier.Classified classified = classifiedFrom(
                device.getId(), cert, "CERT_EXPIRED", "machine-cert:" + cert.getId());
        return autoIngestService.ingestClassified(
                tenantId,
                device.getId(),
                ROLLOUT_ID_ACTIVE_CERT_EXPIRED,
                WAVE_ID,
                classified,
                CLASSIFIER_VERSION,
                "cert_identity:active_cert_expired:" + cert.getId(),
                now);
    }

    boolean ingestFailedTpmEnrollment(EndpointEnrollment enrollment, Instant now) {
        if (enrollment == null || enrollment.getId() == null || enrollment.getStatus() != EnrollmentStatus.TPM_FAILED
                || enrollment.getDevice() == null) {
            return false;
        }
        EndpointDevice device = enrollment.getDevice();
        UUID tenantId = matchingTenantId(device, enrollment.getTenantId(), "enrollment", enrollment.getId());
        if (tenantId == null || device.getId() == null || device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            return false;
        }

        EndpointMachineCert activeCert = certRepository.findActiveByTenantIdAndDeviceId(tenantId, device.getId())
                .orElse(null);
        RolloutFailureClassifier.Classified classified = classifiedFrom(
                device.getId(), activeCert, EnrollmentStatus.TPM_FAILED.name(), "enrollment:" + enrollment.getId());
        return autoIngestService.ingestClassified(
                tenantId,
                device.getId(),
                ROLLOUT_ID_TPM_ENROLLMENT_FAILED,
                WAVE_ID,
                classified,
                CLASSIFIER_VERSION,
                "cert_identity:tpm_failed_enrollment:" + enrollment.getId(),
                now);
    }

    private RolloutFailureClassifier.Classified classifiedFrom(UUID deviceId, EndpointMachineCert cert,
                                                               String enrollmentStatus, String auditEventId) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("class", RolloutFailureClass.CERT_IDENTITY.name());
        evidence.put("device_id", deviceId.toString());
        putNullable(evidence, "cert_fingerprint_prefix", cert == null ? null : hexPrefix(cert.getCertThumbprint()));
        putNullable(evidence, "issuer_id", cert == null ? null : issuerId(cert.getCertIssuer()));
        putNullable(evidence, "subject_san_hash", cert == null ? null : sanHash(cert.getSanUri()));
        evidence.put("enrollment_status", safeStatus(enrollmentStatus));
        putNullable(evidence, "cert_not_before", cert == null || cert.getCertNotBefore() == null
                ? null : cert.getCertNotBefore().toString());
        putNullable(evidence, "cert_not_after", cert == null || cert.getCertNotAfter() == null
                ? null : cert.getCertNotAfter().toString());
        putNullable(evidence, "audit_event_id", auditEventId);
        return new RolloutFailureClassifier.Classified(
                RolloutFailureClass.CERT_IDENTITY,
                RolloutClassificationConfidence.HIGH,
                evidence);
    }

    private static UUID matchingTenantId(EndpointDevice device, UUID sourceTenantId, String sourceKind, UUID sourceId) {
        if (device == null) {
            return sourceTenantId;
        }
        UUID deviceTenantId = device.getEffectiveOrgId();
        if (deviceTenantId == null || sourceTenantId == null || !deviceTenantId.equals(sourceTenantId)) {
            log.warn("Skipping CERT_IDENTITY {} source {} because source tenant does not match device tenant",
                    sourceKind, sourceId);
            return null;
        }
        return deviceTenantId;
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String hexPrefix(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if (!HEX.matcher(normalized).matches()) {
            return sha256Hex(normalized).substring(0, 16);
        }
        return normalized.substring(0, Math.min(16, normalized.length()));
    }

    private static String issuerId(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return null;
        }
        return "issuer-sha256-" + sha256Hex(issuer).substring(0, 16);
    }

    private static String safeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "UNKNOWN";
        }
        return status.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_:-]", "_");
    }

    private static String sanHash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return sha256Hex(value.trim());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HEX_FORMAT.formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
