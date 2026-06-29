package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureActorType;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureEventType;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.repository.EndpointRolloutFailureEventRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RolloutFailureEscalationPublishServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-29T05:30:00Z");

    private final EndpointRolloutFailureRepository failureRepository =
            mock(EndpointRolloutFailureRepository.class);
    private final EndpointRolloutFailureEventRepository eventRepository =
            mock(EndpointRolloutFailureEventRepository.class);
    private final RolloutFailureEscalationGenerator generator =
            mock(RolloutFailureEscalationGenerator.class);
    private final RolloutFailureIssuePublisher publisher =
            mock(RolloutFailureIssuePublisher.class);

    @Test
    void disabledIntegrationFailsClosedBeforePublishing() {
        UUID tenant = UUID.randomUUID();
        EndpointRolloutFailure failure = failure(RolloutFailureState.NEW);
        when(failureRepository.findByTenantIdAndIdForUpdate(tenant, failure.getId()))
                .thenReturn(Optional.of(failure));

        RolloutFailureEscalationPublishService service = service(false);
        assertThatThrownBy(() -> service.publish(tenant, "op@acik", failure.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("github_escalation_disabled");

        verify(publisher, never()).createIssue(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void existingIssueUrlIsIdempotentAndDoesNotPostAgain() {
        UUID tenant = UUID.randomUUID();
        EndpointRolloutFailure failure = failure(RolloutFailureState.ESCALATED);
        failure.setEscalationIssueUrl("https://github.com/Halildeu/platform-backend/issues/9001");
        when(failureRepository.findByTenantIdAndIdForUpdate(tenant, failure.getId()))
                .thenReturn(Optional.of(failure));
        when(generator.generate(any())).thenReturn(new RolloutFailureEscalationResponse(
                "title", "body", List.of("rollout-failure"), failure.getId()));

        var response = service(true).publish(tenant, "op@acik", failure.getId());

        assertThat(response.alreadyPublished()).isTrue();
        assertThat(response.issueUrl()).isEqualTo("https://github.com/Halildeu/platform-backend/issues/9001");
        verify(publisher, never()).createIssue(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void terminalStateCannotBePublished() {
        UUID tenant = UUID.randomUUID();
        EndpointRolloutFailure failure = failure(RolloutFailureState.RESOLVED);
        when(failureRepository.findByTenantIdAndIdForUpdate(tenant, failure.getId()))
                .thenReturn(Optional.of(failure));

        assertThatThrownBy(() -> service(true).publish(tenant, "op@acik", failure.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not publish-escalatable");

        verify(publisher, never()).createIssue(any());
    }

    @Test
    void publishCreatesGithubIssueTransitionsToEscalatedAndWritesLedgerEvent() {
        UUID tenant = UUID.randomUUID();
        EndpointRolloutFailure failure = failure(RolloutFailureState.RETRYING);
        when(failureRepository.findByTenantIdAndIdForUpdate(tenant, failure.getId()))
                .thenReturn(Optional.of(failure));
        RolloutFailureEscalationResponse generated = new RolloutFailureEscalationResponse(
                "Rollout Failure Escalation — SERVICE_HMAC_MODE / wave-1",
                "body",
                List.of("rollout-failure", "class:SERVICE_HMAC_MODE"),
                failure.getId());
        when(generator.generate(any())).thenReturn(generated);
        when(publisher.createIssue(generated)).thenReturn(
                new RolloutFailureIssuePublisher.PublishedIssue(
                        "https://github.com/Halildeu/platform-backend/issues/9001", 9001));

        var response = service(true).publish(tenant, "op@acik", failure.getId());

        assertThat(response.alreadyPublished()).isFalse();
        assertThat(response.issueNumber()).isEqualTo(9001);
        assertThat(response.currentState()).isEqualTo("escalated");
        assertThat(failure.getCurrentState()).isEqualTo(RolloutFailureState.ESCALATED);
        assertThat(failure.getEscalationIssueUrl())
                .isEqualTo("https://github.com/Halildeu/platform-backend/issues/9001");
        assertThat(failure.getLastTransitionAt()).isEqualTo(NOW);
        verify(failureRepository).saveAndFlush(failure);

        ArgumentCaptor<EndpointRolloutFailureEvent> event =
                ArgumentCaptor.forClass(EndpointRolloutFailureEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());
        assertThat(event.getValue().getTenantId()).isEqualTo(tenant);
        assertThat(event.getValue().getFailureId()).isEqualTo(failure.getId());
        assertThat(event.getValue().getEventType()).isEqualTo(RolloutFailureEventType.ESCALATED);
        assertThat(event.getValue().getFromState()).isEqualTo(RolloutFailureState.RETRYING);
        assertThat(event.getValue().getToState()).isEqualTo(RolloutFailureState.ESCALATED);
        assertThat(event.getValue().getActorType()).isEqualTo(RolloutFailureActorType.OPERATOR);
        assertThat(event.getValue().getActorSubjectHash()).matches("^[0-9a-f]{64}$");
        assertThat(event.getValue().getSourceSignal()).isEqualTo("github_issue:9001");
    }

    private RolloutFailureEscalationPublishService service(boolean enabled) {
        return new RolloutFailureEscalationPublishService(
                failureRepository,
                eventRepository,
                generator,
                publisher,
                new RolloutFailureGithubEscalationProperties(enabled, "https://api.github.test",
                        "Halildeu", "platform-backend", enabled ? "token" : "",
                        "test-agent", Duration.ofSeconds(1), Duration.ofSeconds(1), 4096),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static EndpointRolloutFailure failure(RolloutFailureState state) {
        EndpointRolloutFailure failure = new EndpointRolloutFailure();
        failure.setId(UUID.randomUUID());
        failure.setTenantId(UUID.randomUUID());
        failure.setRolloutId("rollout-1");
        failure.setWaveId("wave-1");
        failure.setDeviceId(UUID.randomUUID());
        failure.setCurrentClass(RolloutFailureClass.SERVICE_HMAC_MODE);
        failure.setCurrentState(state);
        failure.setRetryCount(1);
        failure.setMaxRetries(2);
        failure.setFirstDetectedAt(NOW.minusSeconds(3600));
        failure.setLastObservedAt(NOW.minusSeconds(60));
        failure.setLastTransitionAt(NOW.minusSeconds(120));
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("class", "SERVICE_HMAC_MODE");
        evidence.put("device_id", failure.getDeviceId().toString());
        evidence.put("service_state", "stopped");
        evidence.put("agent_mode", "hmac");
        evidence.put("hmac_error_code", null);
        evidence.put("last_heartbeat_at", NOW.minusSeconds(900).toString());
        evidence.put("command_id", null);
        evidence.put("agent_version", "0.3.1");
        failure.setEvidenceRedacted(evidence);
        failure.setOwnerRole("platform-agent");
        failure.setClassificationConfidence(RolloutClassificationConfidence.HIGH);
        failure.setClassifierVersion("auto:heartbeat-stale:v1");
        return failure;
    }
}
