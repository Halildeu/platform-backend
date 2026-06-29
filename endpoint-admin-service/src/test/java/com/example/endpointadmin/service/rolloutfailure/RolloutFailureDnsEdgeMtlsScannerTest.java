package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.repository.EndpointDiagnosticsSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RolloutFailureDnsEdgeMtlsScannerTest {

    private static final Instant NOW = Instant.parse("2026-06-29T10:30:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-06-29T10:00:00Z");
    private static final String CONFIG_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final EndpointDiagnosticsSnapshotRepository diagnosticsRepository =
            mock(EndpointDiagnosticsSnapshotRepository.class);
    private final RolloutFailureAutoIngestService autoIngestService =
            mock(RolloutFailureAutoIngestService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RolloutFailureEvidenceValidator validator =
            new RolloutFailureEvidenceValidator(objectMapper);
    private final RolloutFailureDnsEdgeMtlsScanner scanner =
            new RolloutFailureDnsEdgeMtlsScanner(
                    diagnosticsRepository,
                    autoIngestService,
                    objectMapper,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    10);

    @Test
    void scanCreatesSchemaValidDnsFailureSignal() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        EndpointDiagnosticsSnapshot snapshot = snapshot(tenant, deviceId, snapshotId);
        snapshot.setBackendDnsReachable(false);
        snapshot.setBackendTlsValid(true);
        snapshot.setLastErrorCode("DNS_NXDOMAIN");
        when(diagnosticsRepository.findLatestDnsTlsFailuresExcludingDeviceStatus(
                DeviceStatus.DECOMMISSIONED, PageRequest.of(0, 10)))
                .thenReturn(List.of(snapshot));
        when(autoIngestService.ingestClassified(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        assertThat(scanner.scanDnsEdgeMtlsFailures()).isEqualTo(1);

        ArgumentCaptor<RolloutFailureClassifier.Classified> classified =
                ArgumentCaptor.forClass(RolloutFailureClassifier.Classified.class);
        verify(autoIngestService).ingestClassified(
                eq(tenant),
                eq(deviceId),
                eq(RolloutFailureDnsEdgeMtlsScanner.ROLLOUT_ID),
                eq(RolloutFailureDnsEdgeMtlsScanner.WAVE_ID),
                classified.capture(),
                eq(RolloutFailureDnsEdgeMtlsScanner.CLASSIFIER_VERSION),
                eq("dns_edge_mtls:diagnostics:" + snapshotId),
                eq(NOW));

        RolloutFailureClassifier.Classified c = classified.getValue();
        assertThat(c.failureClass()).isEqualTo(RolloutFailureClass.DNS_EDGE_MTLS);
        assertThat(c.confidence()).isEqualTo(RolloutClassificationConfidence.MEDIUM);
        assertThat(c.evidence().get("endpoint_host_hash").asText()).isEqualTo(CONFIG_HASH);
        assertThat(c.evidence().get("edge_target").asText())
                .isEqualTo(RolloutFailureDnsEdgeMtlsScanner.EDGE_TARGET);
        assertThat(c.evidence().get("dns_error_code").asText()).isEqualTo("DNS_NXDOMAIN");
        assertThat(c.evidence().get("tls_alert").isNull()).isTrue();
        assertThat(c.evidence().get("mtls_peer_cert_fingerprint_prefix").isNull()).isTrue();
        assertThat(c.evidence().get("observed_at").asText()).isEqualTo(OBSERVED.toString());
        assertThat(c.evidence().get("source").asText()).isEqualTo("agent-diagnostics:" + snapshotId);
        assertThatCode(() -> validator.validate(c.failureClass(), c.evidence())).doesNotThrowAnyException();
    }

    @Test
    void tlsInvalidCreatesTlsAlertWithoutClaimingPeerCertFingerprint() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        EndpointDiagnosticsSnapshot snapshot = snapshot(tenant, deviceId, snapshotId);
        snapshot.setBackendDnsReachable(true);
        snapshot.setBackendTlsValid(false);
        snapshot.setLastErrorCode("TLS_CERT_EXPIRED");
        snapshot.setLastErrorOccurredAt(null);
        when(diagnosticsRepository.findLatestDnsTlsFailuresExcludingDeviceStatus(any(), any()))
                .thenReturn(List.of(snapshot));
        when(autoIngestService.ingestClassified(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        assertThat(scanner.scanDnsEdgeMtlsFailures()).isEqualTo(1);

        ArgumentCaptor<RolloutFailureClassifier.Classified> classified =
                ArgumentCaptor.forClass(RolloutFailureClassifier.Classified.class);
        verify(autoIngestService).ingestClassified(any(), any(), any(), any(),
                classified.capture(), any(), any(), any());
        RolloutFailureClassifier.Classified c = classified.getValue();
        assertThat(c.evidence().get("dns_error_code").isNull()).isTrue();
        assertThat(c.evidence().get("tls_alert").asText()).isEqualTo("TLS_CERT_EXPIRED");
        assertThat(c.evidence().get("mtls_peer_cert_fingerprint_prefix").isNull()).isTrue();
        assertThat(c.evidence().get("observed_at").asText()).isEqualTo(snapshot.getCollectedAt().toString());
        assertThatCode(() -> validator.validate(c.failureClass(), c.evidence())).doesNotThrowAnyException();
    }

    @Test
    void dualDnsAndTlsFailureDoesNotCopyOneErrorCodeIntoBothFields() {
        EndpointDiagnosticsSnapshot snapshot = snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        snapshot.setBackendDnsReachable(false);
        snapshot.setBackendTlsValid(false);
        snapshot.setLastErrorCode("DNS_NXDOMAIN");
        when(diagnosticsRepository.findLatestDnsTlsFailuresExcludingDeviceStatus(any(), any()))
                .thenReturn(List.of(snapshot));
        when(autoIngestService.ingestClassified(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        assertThat(scanner.scanDnsEdgeMtlsFailures()).isEqualTo(1);

        ArgumentCaptor<RolloutFailureClassifier.Classified> classified =
                ArgumentCaptor.forClass(RolloutFailureClassifier.Classified.class);
        verify(autoIngestService).ingestClassified(any(), any(), any(), any(),
                classified.capture(), any(), any(), any());
        RolloutFailureClassifier.Classified c = classified.getValue();
        assertThat(c.evidence().get("dns_error_code").asText()).isEqualTo("DNS_NXDOMAIN");
        assertThat(c.evidence().get("tls_alert").isNull()).isTrue();
        assertThatCode(() -> validator.validate(c.failureClass(), c.evidence())).doesNotThrowAnyException();
    }

    @Test
    void healthySnapshotIsSkippedEvenIfRepositoryReturnsItDefensively() {
        EndpointDiagnosticsSnapshot snapshot = snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        snapshot.setBackendDnsReachable(true);
        snapshot.setBackendTlsValid(true);
        when(diagnosticsRepository.findLatestDnsTlsFailuresExcludingDeviceStatus(any(), any()))
                .thenReturn(List.of(snapshot));

        assertThat(scanner.scanDnsEdgeMtlsFailures()).isZero();

        verify(autoIngestService, never()).ingestClassified(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownConfigHashIsSkippedRatherThanFabricatingEndpointHash() {
        EndpointDiagnosticsSnapshot snapshot = snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        snapshot.setBackendDnsReachable(false);
        snapshot.setBackendTlsValid(true);
        snapshot.setConfigHash("unknown");
        when(diagnosticsRepository.findLatestDnsTlsFailuresExcludingDeviceStatus(any(), any()))
                .thenReturn(List.of(snapshot));

        assertThat(scanner.scanDnsEdgeMtlsFailures()).isZero();

        verify(autoIngestService, never()).ingestClassified(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingObservedTimestampIsSkippedRatherThanInventingEpoch() {
        EndpointDiagnosticsSnapshot snapshot = snapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        snapshot.setBackendDnsReachable(false);
        snapshot.setBackendTlsValid(true);
        snapshot.setLastErrorOccurredAt(null);
        snapshot.setCollectedAt(null);
        when(diagnosticsRepository.findLatestDnsTlsFailuresExcludingDeviceStatus(any(), any()))
                .thenReturn(List.of(snapshot));

        assertThat(scanner.scanDnsEdgeMtlsFailures()).isZero();

        verify(autoIngestService, never()).ingestClassified(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static EndpointDiagnosticsSnapshot snapshot(UUID tenant, UUID deviceId, UUID snapshotId) {
        EndpointDiagnosticsSnapshot snapshot = new EndpointDiagnosticsSnapshot();
        snapshot.setId(snapshotId);
        snapshot.setTenantId(tenant);
        snapshot.setDeviceId(deviceId);
        snapshot.setConfigHash(CONFIG_HASH);
        snapshot.setBackendDnsReachable(false);
        snapshot.setBackendTlsValid(true);
        snapshot.setLastErrorOccurredAt(OBSERVED);
        snapshot.setCollectedAt(OBSERVED.plusSeconds(60));
        return snapshot;
    }
}
