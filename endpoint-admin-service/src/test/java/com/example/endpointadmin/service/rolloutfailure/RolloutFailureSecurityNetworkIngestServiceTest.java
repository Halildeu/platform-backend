package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.security.SecurityNetworkPayloadPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RolloutFailureSecurityNetworkIngestServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RESULT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant REPORTED_AT = Instant.parse("2026-06-29T11:20:00Z");

    @Mock private EndpointCommandResultRepository resultRepository;
    @Mock private RolloutFailureAutoIngestService autoIngestService;

    @Test
    void ingestIgnoresNullEventBeforeRepositoryLookup() {
        int count = newService().ingest(null);

        assertThat(count).isZero();
        verifyNoInteractions(resultRepository, autoIngestService);
    }

    @Test
    void ingestIgnoresMissingResult() {
        when(resultRepository.findById(RESULT_ID)).thenReturn(Optional.empty());

        int count = newService().ingest(new SecurityNetworkBlockSubmittedEvent(TENANT_ID, DEVICE_ID, RESULT_ID));

        assertThat(count).isZero();
        verifyNoInteractions(autoIngestService);
    }

    @Test
    void ingestIgnoresResultWithoutSecurityNetworkBlock() {
        EndpointCommandResult result = org.mockito.Mockito.mock(EndpointCommandResult.class);
        when(result.getResultPayload()).thenReturn(Map.of("details", Map.of("inventory", Map.of())));
        when(resultRepository.findById(RESULT_ID)).thenReturn(Optional.of(result));

        int count = newService().ingest(new SecurityNetworkBlockSubmittedEvent(TENANT_ID, DEVICE_ID, RESULT_ID));

        assertThat(count).isZero();
        verifyNoInteractions(autoIngestService);
    }

    @Test
    void ingestTurnsStructuredSecurityNetworkEventIntoEdrNetworkQueueEvidence() {
        EndpointCommandResult result = org.mockito.Mockito.mock(EndpointCommandResult.class);
        when(result.getReportedAt()).thenReturn(REPORTED_AT);
        when(result.getResultPayload()).thenReturn(Map.of(
                        "details", Map.of(
                        "inventory", Map.of(
                                "securityNetwork", validBlock()))));
        when(resultRepository.findById(RESULT_ID)).thenReturn(Optional.of(result));
        when(autoIngestService.ingestClassified(eq(TENANT_ID), eq(DEVICE_ID),
                eq(RolloutFailureSecurityNetworkIngestService.ROLLOUT_ID),
                eq(RolloutFailureSecurityNetworkIngestService.WAVE_ID),
                any(), eq(RolloutFailureSecurityNetworkIngestService.CLASSIFIER_VERSION),
                eq("security_network:" + RESULT_ID + ":0"), eq(REPORTED_AT))).thenReturn(true);

        int count = newService().ingest(new SecurityNetworkBlockSubmittedEvent(TENANT_ID, DEVICE_ID, RESULT_ID));

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<RolloutFailureClassifier.Classified> captor =
                ArgumentCaptor.forClass(RolloutFailureClassifier.Classified.class);
        verify(autoIngestService).ingestClassified(eq(TENANT_ID), eq(DEVICE_ID),
                eq(RolloutFailureSecurityNetworkIngestService.ROLLOUT_ID),
                eq(RolloutFailureSecurityNetworkIngestService.WAVE_ID),
                captor.capture(),
                eq(RolloutFailureSecurityNetworkIngestService.CLASSIFIER_VERSION),
                eq("security_network:" + RESULT_ID + ":0"), eq(REPORTED_AT));
        RolloutFailureClassifier.Classified classified = captor.getValue();
        assertThat(classified.failureClass()).isEqualTo(RolloutFailureClass.EDR_NETWORK);
        assertThat(classified.evidence().path("class").asText()).isEqualTo("EDR_NETWORK");
        assertThat(classified.evidence().path("device_id").asText()).isEqualTo(DEVICE_ID.toString());
        assertThat(classified.evidence().path("edr_vendor").asText()).isEqualTo("windows-firewall");
        assertThat(classified.evidence().path("blocked_process_hash_prefix").asText())
                .isEqualTo("0123456789abcdef");
        assertThat(classified.evidence().path("blocked_destination").asText())
                .isEqualTo("dest-sha256-0123456789abcdef");
        assertThat(classified.evidence().path("firewall_rule_id").asText()).isEqualTo("wfp-filter-5157");
    }

    private RolloutFailureSecurityNetworkIngestService newService() {
        return new RolloutFailureSecurityNetworkIngestService(
                resultRepository,
                new SecurityNetworkPayloadPolicy(),
                autoIngestService,
                new ObjectMapper());
    }

    private static Map<String, Object> validBlock() {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("networkSegmentId", "pilot-segment-a");
        event.put("edrVendor", "windows-firewall");
        event.put("blockedProcessHashPrefix", "0123456789abcdef");
        event.put("blockedDestination", "dest-sha256-0123456789abcdef");
        event.put("firewallRuleId", "wfp-filter-5157");
        event.put("lastSuccessfulContactAt", "2026-06-29T11:10:00Z");
        event.put("observedAt", "2026-06-29T11:15:00Z");

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("schemaVersion", 1);
        block.put("supported", true);
        block.put("probeComplete", true);
        block.put("events", List.of(event));
        block.put("probeErrors", List.of());
        block.put("probeDurationMs", 42);
        return block;
    }
}
