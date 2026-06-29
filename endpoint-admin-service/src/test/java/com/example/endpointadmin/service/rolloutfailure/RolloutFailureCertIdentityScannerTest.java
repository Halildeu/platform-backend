package com.example.endpointadmin.service.rolloutfailure;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RolloutFailureCertIdentityScannerTest {

    private static final Instant NOW = Instant.parse("2026-06-29T08:00:00Z");
    private static final Instant NOT_BEFORE = Instant.parse("2026-05-29T08:00:00Z");
    private static final Instant NOT_AFTER = Instant.parse("2026-06-28T08:00:00Z");

    private final EndpointMachineCertRepository certRepository = mock(EndpointMachineCertRepository.class);
    private final EndpointEnrollmentRepository enrollmentRepository = mock(EndpointEnrollmentRepository.class);
    private final RolloutFailureAutoIngestService autoIngestService = mock(RolloutFailureAutoIngestService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RolloutFailureEvidenceValidator validator = new RolloutFailureEvidenceValidator(objectMapper);
    private final RolloutFailureCertIdentityScanner scanner = new RolloutFailureCertIdentityScanner(
            certRepository,
            enrollmentRepository,
            autoIngestService,
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC),
            10);

    @Test
    void scanCreatesSchemaValidExpiredActiveCertSignal() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID certId = UUID.randomUUID();
        EndpointMachineCert cert = cert(tenant, deviceId, certId);
        when(certRepository.findExpiredActiveCerts(
                NOW, DeviceStatus.DECOMMISSIONED, PageRequest.of(0, 10)))
                .thenReturn(List.of(cert));
        when(enrollmentRepository.findDeviceBoundByStatusExcludingDeviceStatus(
                EnrollmentStatus.TPM_FAILED, DeviceStatus.DECOMMISSIONED, PageRequest.of(0, 10)))
                .thenReturn(List.of());
        when(autoIngestService.ingestClassified(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        assertThat(scanner.scanCertIdentityFailures()).isEqualTo(1);

        ArgumentCaptor<RolloutFailureClassifier.Classified> classified =
                ArgumentCaptor.forClass(RolloutFailureClassifier.Classified.class);
        verify(autoIngestService).ingestClassified(
                eq(tenant),
                eq(deviceId),
                eq(RolloutFailureCertIdentityScanner.ROLLOUT_ID_ACTIVE_CERT_EXPIRED),
                eq(RolloutFailureCertIdentityScanner.WAVE_ID),
                classified.capture(),
                eq(RolloutFailureCertIdentityScanner.CLASSIFIER_VERSION),
                eq("cert_identity:active_cert_expired:" + certId),
                eq(NOW));

        RolloutFailureClassifier.Classified c = classified.getValue();
        assertThat(c.failureClass()).isEqualTo(RolloutFailureClass.CERT_IDENTITY);
        assertThat(c.confidence()).isEqualTo(RolloutClassificationConfidence.HIGH);
        assertThat(c.evidence().get("cert_fingerprint_prefix").asText()).hasSize(16);
        assertThat(c.evidence().get("issuer_id").asText()).startsWith("issuer-sha256-");
        assertThat(c.evidence().get("subject_san_hash").asText()).matches("[0-9a-f]{64}");
        assertThat(c.evidence().get("enrollment_status").asText()).isEqualTo("CERT_EXPIRED");
        assertThat(c.evidence().get("cert_not_before").asText()).isEqualTo(NOT_BEFORE.toString());
        assertThat(c.evidence().get("cert_not_after").asText()).isEqualTo(NOT_AFTER.toString());
        assertThat(c.evidence().get("audit_event_id").asText()).isEqualTo("machine-cert:" + certId);
        assertThatCode(() -> validator.validate(c.failureClass(), c.evidence())).doesNotThrowAnyException();
    }

    @Test
    void tpmFailedEnrollmentWithoutActiveCertStillCreatesDeviceBoundSignalWithNullCertFields() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID enrollmentId = UUID.randomUUID();
        EndpointEnrollment enrollment = enrollment(tenant, deviceId, enrollmentId);
        when(certRepository.findExpiredActiveCerts(any(), any(), any())).thenReturn(List.of());
        when(enrollmentRepository.findDeviceBoundByStatusExcludingDeviceStatus(
                EnrollmentStatus.TPM_FAILED, DeviceStatus.DECOMMISSIONED, PageRequest.of(0, 10)))
                .thenReturn(List.of(enrollment));
        when(certRepository.findActiveByTenantIdAndDeviceId(tenant, deviceId)).thenReturn(Optional.empty());
        when(autoIngestService.ingestClassified(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        assertThat(scanner.scanCertIdentityFailures()).isEqualTo(1);

        ArgumentCaptor<RolloutFailureClassifier.Classified> classified =
                ArgumentCaptor.forClass(RolloutFailureClassifier.Classified.class);
        verify(autoIngestService).ingestClassified(
                eq(tenant),
                eq(deviceId),
                eq(RolloutFailureCertIdentityScanner.ROLLOUT_ID_TPM_ENROLLMENT_FAILED),
                eq(RolloutFailureCertIdentityScanner.WAVE_ID),
                classified.capture(),
                eq(RolloutFailureCertIdentityScanner.CLASSIFIER_VERSION),
                eq("cert_identity:tpm_failed_enrollment:" + enrollmentId),
                eq(NOW));

        RolloutFailureClassifier.Classified c = classified.getValue();
        assertThat(c.failureClass()).isEqualTo(RolloutFailureClass.CERT_IDENTITY);
        assertThat(c.evidence().get("cert_fingerprint_prefix").isNull()).isTrue();
        assertThat(c.evidence().get("issuer_id").isNull()).isTrue();
        assertThat(c.evidence().get("subject_san_hash").isNull()).isTrue();
        assertThat(c.evidence().get("enrollment_status").asText()).isEqualTo("TPM_FAILED");
        assertThat(c.evidence().get("audit_event_id").asText()).isEqualTo("enrollment:" + enrollmentId);
        assertThatCode(() -> validator.validate(c.failureClass(), c.evidence())).doesNotThrowAnyException();
    }

    @Test
    void blankCertIdentifiersRemainNullRatherThanHashingAbsence() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID certId = UUID.randomUUID();
        EndpointMachineCert cert = cert(tenant, deviceId, certId);
        when(cert.getSanUri()).thenReturn(" ");
        when(cert.getCertThumbprint()).thenReturn(" ");
        when(certRepository.findExpiredActiveCerts(any(), any(), any())).thenReturn(List.of(cert));
        when(enrollmentRepository.findDeviceBoundByStatusExcludingDeviceStatus(any(), any(), any()))
                .thenReturn(List.of());
        when(autoIngestService.ingestClassified(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        assertThat(scanner.scanCertIdentityFailures()).isEqualTo(1);

        ArgumentCaptor<RolloutFailureClassifier.Classified> classified =
                ArgumentCaptor.forClass(RolloutFailureClassifier.Classified.class);
        verify(autoIngestService).ingestClassified(
                eq(tenant),
                eq(deviceId),
                eq(RolloutFailureCertIdentityScanner.ROLLOUT_ID_ACTIVE_CERT_EXPIRED),
                eq(RolloutFailureCertIdentityScanner.WAVE_ID),
                classified.capture(),
                eq(RolloutFailureCertIdentityScanner.CLASSIFIER_VERSION),
                eq("cert_identity:active_cert_expired:" + certId),
                eq(NOW));

        RolloutFailureClassifier.Classified c = classified.getValue();
        assertThat(c.evidence().get("cert_fingerprint_prefix").isNull()).isTrue();
        assertThat(c.evidence().get("subject_san_hash").isNull()).isTrue();
        assertThatCode(() -> validator.validate(c.failureClass(), c.evidence())).doesNotThrowAnyException();
    }

    @Test
    void sourceTenantMismatchIsSkippedFailClosed() {
        UUID sourceTenant = UUID.randomUUID();
        UUID deviceTenant = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID certId = UUID.randomUUID();
        EndpointDevice device = device(deviceTenant, deviceId);
        EndpointMachineCert cert = mock(EndpointMachineCert.class);
        when(cert.getId()).thenReturn(certId);
        when(cert.getTenantId()).thenReturn(sourceTenant);
        when(cert.getDevice()).thenReturn(device);
        when(cert.getCertNotAfter()).thenReturn(NOT_AFTER);
        when(certRepository.findExpiredActiveCerts(any(), any(), any())).thenReturn(List.of(cert));
        when(enrollmentRepository.findDeviceBoundByStatusExcludingDeviceStatus(any(), any(), any()))
                .thenReturn(List.of());

        assertThat(scanner.scanCertIdentityFailures()).isZero();

        verify(autoIngestService, never())
                .ingestClassified(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deviceLessTpmFailureIsSkippedRatherThanFabricatingDeviceIdentity() {
        EndpointEnrollment enrollment = mock(EndpointEnrollment.class);
        when(enrollment.getId()).thenReturn(UUID.randomUUID());
        when(enrollment.getStatus()).thenReturn(EnrollmentStatus.TPM_FAILED);
        when(enrollment.getDevice()).thenReturn(null);
        when(certRepository.findExpiredActiveCerts(any(), any(), any())).thenReturn(List.of());
        when(enrollmentRepository.findDeviceBoundByStatusExcludingDeviceStatus(any(), any(), any()))
                .thenReturn(List.of(enrollment));

        assertThat(scanner.scanCertIdentityFailures()).isZero();

        verify(autoIngestService, never())
                .ingestClassified(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static EndpointDevice device(UUID tenant, UUID deviceId) {
        EndpointDevice device = mock(EndpointDevice.class);
        when(device.getId()).thenReturn(deviceId);
        when(device.getEffectiveOrgId()).thenReturn(tenant);
        when(device.getStatus()).thenReturn(DeviceStatus.ONLINE);
        return device;
    }

    private static EndpointMachineCert cert(UUID tenant, UUID deviceId, UUID certId) {
        EndpointMachineCert cert = mock(EndpointMachineCert.class);
        EndpointDevice device = device(tenant, deviceId);
        when(cert.getId()).thenReturn(certId);
        when(cert.getTenantId()).thenReturn(tenant);
        when(cert.getDevice()).thenReturn(device);
        when(cert.getSanUri()).thenReturn("adcomputer:" + UUID.randomUUID());
        when(cert.getCertThumbprint()).thenReturn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(cert.getCertIssuer()).thenReturn("CN=Platform Device Issuer,O=Acik Holding");
        when(cert.getCertNotBefore()).thenReturn(NOT_BEFORE);
        when(cert.getCertNotAfter()).thenReturn(NOT_AFTER);
        return cert;
    }

    private static EndpointEnrollment enrollment(UUID tenant, UUID deviceId, UUID enrollmentId) {
        EndpointEnrollment enrollment = mock(EndpointEnrollment.class);
        EndpointDevice device = device(tenant, deviceId);
        when(enrollment.getId()).thenReturn(enrollmentId);
        when(enrollment.getTenantId()).thenReturn(tenant);
        when(enrollment.getStatus()).thenReturn(EnrollmentStatus.TPM_FAILED);
        when(enrollment.getDevice()).thenReturn(device);
        return enrollment;
    }
}
