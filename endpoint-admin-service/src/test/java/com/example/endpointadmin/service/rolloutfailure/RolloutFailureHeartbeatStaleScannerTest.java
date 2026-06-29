package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RolloutFailureHeartbeatStaleScannerTest {

    private static final Instant NOW = Instant.parse("2026-06-29T06:30:00Z");
    private static final Instant LAST_HEARTBEAT = Instant.parse("2026-06-28T05:00:00Z");

    private final EndpointHeartbeatRepository heartbeatRepository = mock(EndpointHeartbeatRepository.class);
    private final RolloutFailureAutoIngestService autoIngestService = mock(RolloutFailureAutoIngestService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RolloutFailureEvidenceValidator validator = new RolloutFailureEvidenceValidator(objectMapper);
    private final RolloutFailureHeartbeatStaleScanner scanner =
            new RolloutFailureHeartbeatStaleScanner(
                    heartbeatRepository,
                    autoIngestService,
                    objectMapper,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    Duration.ofHours(24),
                    10);

    @Test
    void scanCreatesSchemaValidHeartbeatStaleSignal() {
        UUID tenant = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID heartbeatId = UUID.randomUUID();
        EndpointHeartbeat heartbeat = heartbeat(tenant, deviceId, heartbeatId,
                Map.of("state", "ONLINE", "agentMode", "hmac"));
        when(heartbeatRepository.findLatestStaleHeartbeats(
                NOW.minus(Duration.ofHours(24)), DeviceStatus.DECOMMISSIONED, PageRequest.of(0, 10)))
                .thenReturn(List.of(heartbeat));
        when(autoIngestService.ingestClassified(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        assertThat(scanner.scanStaleHeartbeats()).isEqualTo(1);

        ArgumentCaptor<RolloutFailureClassifier.Classified> classified =
                ArgumentCaptor.forClass(RolloutFailureClassifier.Classified.class);
        verify(autoIngestService).ingestClassified(
                eq(tenant),
                eq(deviceId),
                eq(RolloutFailureHeartbeatStaleScanner.ROLLOUT_ID),
                eq(RolloutFailureHeartbeatStaleScanner.WAVE_ID),
                classified.capture(),
                eq(RolloutFailureHeartbeatStaleScanner.CLASSIFIER_VERSION),
                eq("heartbeat_stale:" + heartbeatId),
                eq(NOW));

        RolloutFailureClassifier.Classified c = classified.getValue();
        assertThat(c.failureClass()).isEqualTo(RolloutFailureClass.SERVICE_HMAC_MODE);
        assertThat(c.confidence()).isEqualTo(RolloutClassificationConfidence.MEDIUM);
        assertThat(c.evidence().get("service_state").asText()).isEqualTo("online");
        assertThat(c.evidence().get("agent_mode").asText()).isEqualTo("hmac");
        assertThat(c.evidence().get("hmac_error_code").isNull()).isTrue();
        assertThat(c.evidence().get("last_heartbeat_at").asText()).isEqualTo(LAST_HEARTBEAT.toString());
        assertThatCode(() -> validator.validate(c.failureClass(), c.evidence())).doesNotThrowAnyException();
    }

    @Test
    void missingAgentModeSkipsRatherThanGuessingEvidence() {
        EndpointHeartbeat heartbeat = heartbeat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Map.of("state", "ONLINE"));
        when(heartbeatRepository.findLatestStaleHeartbeats(
                NOW.minus(Duration.ofHours(24)), DeviceStatus.DECOMMISSIONED, PageRequest.of(0, 10)))
                .thenReturn(List.of(heartbeat));

        assertThat(scanner.scanStaleHeartbeats()).isZero();

        verify(autoIngestService, never()).ingestClassified(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static EndpointHeartbeat heartbeat(UUID tenant, UUID deviceId, UUID heartbeatId,
                                               Map<String, Object> payload) {
        EndpointDevice device = mock(EndpointDevice.class);
        when(device.getId()).thenReturn(deviceId);
        when(device.getEffectiveOrgId()).thenReturn(tenant);
        when(device.getStatus()).thenReturn(DeviceStatus.ONLINE);

        EndpointHeartbeat heartbeat = mock(EndpointHeartbeat.class);
        when(heartbeat.getId()).thenReturn(heartbeatId);
        when(heartbeat.getTenantId()).thenReturn(tenant);
        when(heartbeat.getDevice()).thenReturn(device);
        when(heartbeat.getReceivedAt()).thenReturn(LAST_HEARTBEAT);
        when(heartbeat.getAgentVersion()).thenReturn("0.3.1");
        when(heartbeat.getPayload()).thenReturn(payload);
        return heartbeat;
    }
}
