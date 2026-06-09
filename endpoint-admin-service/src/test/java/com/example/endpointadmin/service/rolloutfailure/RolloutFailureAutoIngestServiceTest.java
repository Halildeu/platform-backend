package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutFailureActorType;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureEventType;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureEventRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mocked-repo coverage of the §9.2 auto-ingest coalesce branches that are
 * awkward to drive through a REQUIRES_NEW PG IT — specifically the forward-looking
 * retrying->retrying self-loop (contract §4) which no slice-2a transition sets.
 */
class RolloutFailureAutoIngestServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EndpointCommandResultRepository resultRepository = mock(EndpointCommandResultRepository.class);
    private final EndpointRolloutFailureRepository failureRepository = mock(EndpointRolloutFailureRepository.class);
    private final EndpointRolloutFailureEventRepository eventRepository = mock(EndpointRolloutFailureEventRepository.class);

    private final RolloutFailureAutoIngestService service = new RolloutFailureAutoIngestService(
            resultRepository, failureRepository, eventRepository,
            new RolloutFailureClassifier(MAPPER), new RolloutFailureEvidenceValidator(MAPPER));

    private final UUID tenant = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();

    private CommandResultFailedEvent msiFailureEvent() {
        UUID resultId = UUID.randomUUID();
        EndpointCommandResult r = mock(EndpointCommandResult.class);
        when(r.getId()).thenReturn(resultId);
        when(r.getResultStatus()).thenReturn(CommandResultStatus.FAILED);
        when(r.getErrorCode()).thenReturn("INSTALL_FAILED_MSI");
        when(r.getExitCode()).thenReturn(1627);
        when(r.getErrorMessage()).thenReturn(null);
        when(r.getResultPayload()).thenReturn(Map.of());
        when(resultRepository.findById(resultId)).thenReturn(Optional.of(r));
        return new CommandResultFailedEvent(tenant, device, resultId, CommandType.INSTALL_SOFTWARE);
    }

    @Test
    void retryingActiveItemAppendsValidRetrySelfLoopEvent() {
        EndpointRolloutFailure active = new EndpointRolloutFailure();
        active.setId(UUID.randomUUID());
        active.setTenantId(tenant);
        active.setDeviceId(device);
        active.setCurrentClass(RolloutFailureClass.INSTALLER_MSI);
        active.setCurrentState(RolloutFailureState.RETRYING);
        when(failureRepository.findByTenantIdAndRolloutIdAndWaveIdAndDeviceId(
                tenant, "cmd-result-auto:INSTALL_SOFTWARE", "INSTALLER_MSI", device))
                .thenReturn(List.of(active));
        when(eventRepository.existsByTenantIdAndFailureIdAndSourceSignal(any(), any(), any()))
                .thenReturn(false);

        assertThat(service.ingest(msiFailureEvent())).isTrue();

        ArgumentCaptor<EndpointRolloutFailureEvent> captor =
                ArgumentCaptor.forClass(EndpointRolloutFailureEvent.class);
        verify(eventRepository).saveAndFlush(captor.capture());
        EndpointRolloutFailureEvent e = captor.getValue();
        assertThat(e.getEventType()).isEqualTo(RolloutFailureEventType.RETRY);
        assertThat(e.getFromState()).isEqualTo(RolloutFailureState.RETRYING);
        assertThat(e.getToState()).isEqualTo(RolloutFailureState.RETRYING);
        assertThat(e.getActorType()).isEqualTo(RolloutFailureActorType.AUTO);
    }

    @Test
    void alreadyLedgeredSourceResultIsNoOp() {
        EndpointRolloutFailure active = new EndpointRolloutFailure();
        active.setId(UUID.randomUUID());
        active.setTenantId(tenant);
        active.setDeviceId(device);
        active.setCurrentClass(RolloutFailureClass.INSTALLER_MSI);
        active.setCurrentState(RolloutFailureState.RETRYING);
        when(failureRepository.findByTenantIdAndRolloutIdAndWaveIdAndDeviceId(
                tenant, "cmd-result-auto:INSTALL_SOFTWARE", "INSTALLER_MSI", device))
                .thenReturn(List.of(active));
        when(eventRepository.existsByTenantIdAndFailureIdAndSourceSignal(any(), any(), any()))
                .thenReturn(true); // this exact source result already ledgered

        assertThat(service.ingest(msiFailureEvent())).isFalse();
        verify(eventRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }
}
