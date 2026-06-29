package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationPublishResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutFailureActorType;
import com.example.endpointadmin.model.RolloutFailureEventType;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.repository.EndpointRolloutFailureEventRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureRepository;
import com.example.endpointadmin.service.RolloutFailureQueueReadService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Publishes the generated #527 escalation projection to GitHub and records the
 * resulting URL on the canonical backend queue item. The issue is a projection;
 * the aggregate and event ledger remain the source of truth.
 */
@Service
public class RolloutFailureEscalationPublishService {

    private static final EnumSet<RolloutFailureState> ESCALATABLE =
            EnumSet.of(RolloutFailureState.NEW, RolloutFailureState.RETRYING, RolloutFailureState.QUARANTINED);

    private final EndpointRolloutFailureRepository failureRepository;
    private final EndpointRolloutFailureEventRepository eventRepository;
    private final RolloutFailureEscalationGenerator generator;
    private final RolloutFailureIssuePublisher publisher;
    private final RolloutFailureGithubEscalationProperties properties;
    private final Clock clock;

    public RolloutFailureEscalationPublishService(
            EndpointRolloutFailureRepository failureRepository,
            EndpointRolloutFailureEventRepository eventRepository,
            RolloutFailureEscalationGenerator generator,
            RolloutFailureIssuePublisher publisher,
            RolloutFailureGithubEscalationProperties properties,
            Clock clock) {
        this.failureRepository = failureRepository;
        this.eventRepository = eventRepository;
        this.generator = generator;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RolloutFailureEscalationPublishResponse publish(UUID tenantId, String subject, UUID failureId) {
        EndpointRolloutFailure failure = failureRepository.findByTenantIdAndIdForUpdate(tenantId, failureId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rollout failure not found"));

        if (hasIssueUrl(failure)) {
            return new RolloutFailureEscalationPublishResponse(
                    failure.getId(), failure.getEscalationIssueUrl(), null,
                    "Rollout Failure Escalation — " + failure.getCurrentClass().name() + " / " + failure.getWaveId(),
                    generator.generate(RolloutFailureQueueReadService.toItem(failure)).labels(),
                    failure.getCurrentState().wire(), true);
        }
        if (!properties.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "github_escalation_disabled");
        }
        RolloutFailureState from = failure.getCurrentState();
        if (!ESCALATABLE.contains(from)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "rollout failure state is not publish-escalatable: " + from.wire());
        }

        RolloutFailureEscalationResponse issue = generator.generate(RolloutFailureQueueReadService.toItem(failure));
        final RolloutFailureIssuePublisher.PublishedIssue published;
        try {
            published = publisher.createIssue(issue);
        } catch (GitHubRolloutFailureIssuePublisher.GitHubIssuePublishException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }

        Instant now = Instant.now(clock);
        failure.setEscalationIssueUrl(published.htmlUrl());
        failure.setCurrentState(RolloutFailureState.ESCALATED);
        failure.setLastTransitionAt(now);
        failureRepository.saveAndFlush(failure);

        EndpointRolloutFailureEvent event = new EndpointRolloutFailureEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(tenantId);
        event.setFailureId(failure.getId());
        event.setEventType(RolloutFailureEventType.ESCALATED);
        event.setFromState(from);
        event.setToState(RolloutFailureState.ESCALATED);
        event.setFailureClass(failure.getCurrentClass());
        event.setSourceSignal("github_issue:" + published.number());
        event.setRedactedEvidence(failure.getEvidenceRedacted());
        event.setActorType(RolloutFailureActorType.OPERATOR);
        event.setActorSubjectHash(subjectHash(subject));
        event.setClassificationConfidence(failure.getClassificationConfidence());
        eventRepository.saveAndFlush(event);

        return new RolloutFailureEscalationPublishResponse(
                failure.getId(), published.htmlUrl(), published.number(), issue.issueTitle(), issue.labels(),
                failure.getCurrentState().wire(), false);
    }

    private static boolean hasIssueUrl(EndpointRolloutFailure failure) {
        return failure.getEscalationIssueUrl() != null && !failure.getEscalationIssueUrl().isBlank();
    }

    private static String subjectHash(String subject) {
        String value = (subject == null || subject.isBlank()) ? "unknown" : subject;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
