package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.ApprovalActorDto;
import com.example.endpointadmin.dto.v1.admin.DecisionRecordDto;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.ApproveRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.AttestRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.DelegateRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.RejectRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyApprovalDecisionRequests.RequestChangesRequest;
import com.example.endpointadmin.dto.v1.admin.PolicyChangeApprovalDto;
import com.example.endpointadmin.dto.v1.admin.ProposePolicyChangeRequest;
import com.example.endpointadmin.exception.PolicyApprovalProposerSelfException;
import com.example.endpointadmin.model.PolicyApprovalDecisionKind;
import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.model.PolicyChangeApproval;
import com.example.endpointadmin.model.PolicyChangeApprovalDecision;
import com.example.endpointadmin.repository.PolicyChangeApprovalRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Wave-12 PR-5 — back-end for the policy-change approval workflow used by
 * the platform-web {@code mfe-endpoint-admin} pilot. The platform-web
 * {@code ApprovalsRepository} port currently writes to {@code
 * localStorage}; once this service is in place the front-end can switch
 * to the {@code approvalsApiRepository} adapter (separate follow-up PR).
 *
 * <p>Governance invariants enforced here:
 * <ul>
 *   <li>4-eyes guard — the proposer cannot {@code APPROVE} their own
 *       request (403 {@code proposer_self});</li>
 *   <li>Decisions are only accepted while the request is in an open
 *       state ({@code PENDING} or {@code IN_REVIEW});</li>
 *   <li>{@code REJECT} and {@code REQUEST_CHANGES} require a reason;</li>
 *   <li>{@code REQUEST_CHANGES} moves a {@code PENDING} request to
 *       {@code IN_REVIEW}; the request stays open for revision;</li>
 *   <li>{@code APPROVE} / {@code REJECT} are terminal — no further
 *       decision is accepted afterwards;</li>
 *   <li>{@code DELEGATE} rewrites {@code currentApprovers} atomically
 *       (the delegating actor is replaced by {@code delegateTo}); does
 *       not advance status.</li>
 * </ul>
 *
 * <p>The append-only {@code history} on each request is itself the audit
 * trail; this service intentionally does NOT write to the device-scoped
 * {@code EndpointAuditService} chain — policy-change approvals are not
 * tied to a single device.
 */
@Service
public class PolicyChangeApprovalService {

    private static final Set<PolicyApprovalStatus> OPEN_STATES =
            EnumSet.of(PolicyApprovalStatus.PENDING, PolicyApprovalStatus.IN_REVIEW);

    private final PolicyChangeApprovalRepository repository;
    private final Clock clock;

    public PolicyChangeApprovalService(PolicyChangeApprovalRepository repository,
                                       Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PolicyChangeApprovalDto> list(AdminTenantContext context,
                                              PolicyApprovalStatus status,
                                              String target,
                                              String proposerSubject) {
        return repository.search(context.tenantId(), status,
                        trimToNull(target), trimToNull(proposerSubject))
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyChangeApprovalDto get(AdminTenantContext context, UUID id) {
        return toDto(loadRequired(context, id));
    }

    @Transactional
    public PolicyChangeApprovalDto propose(AdminTenantContext context,
                                           ProposePolicyChangeRequest request) {
        if (request == null) {
            throw badRequest("A propose request body is required.");
        }
        Instant now = Instant.now(clock);
        if (request.deadline() != null && !request.deadline().isAfter(now)) {
            throw badRequest("Deadline must be in the future.");
        }

        PolicyChangeApproval approval = new PolicyChangeApproval();
        approval.setTenantId(context.tenantId());
        approval.setTitle(request.title().trim());
        approval.setTarget(request.target().trim());
        approval.setProposerSubject(request.proposer().id().trim());
        approval.setProposerName(request.proposer().name().trim());
        approval.setProposerRole(request.proposer().role().trim());
        approval.setReason(request.reason().trim());
        approval.setEvidenceRefs(sanitizeEvidenceRefs(request.evidenceRefs()));
        approval.setChangeKind(request.changeKind());
        approval.setRiskTier(request.riskTier());
        approval.setBeforeState(request.before());
        approval.setAfterState(request.after());
        approval.setDeadline(request.deadline());
        approval.setStatus(PolicyApprovalStatus.PENDING);
        approval.setCurrentApprovers(actorListToJson(request.currentApprovers()));

        PolicyChangeApproval saved = repository.saveAndFlush(approval);
        return toDto(saved);
    }

    @Transactional
    public PolicyChangeApprovalDto approve(AdminTenantContext context, UUID id,
                                           ApproveRequest request) {
        if (request == null) {
            throw badRequest("An approve request body is required.");
        }
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);
        guardProposerSelfApprove(approval, request.actor());

        Instant at = Instant.now(clock);
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.APPROVE, request.actor(), at);
        decision.setReason(trimToNull(request.reason()));
        approval.addDecision(decision);
        approval.setStatus(PolicyApprovalStatus.APPROVED);

        return toDto(repository.saveAndFlush(approval));
    }

    @Transactional
    public PolicyChangeApprovalDto reject(AdminTenantContext context, UUID id,
                                          RejectRequest request) {
        if (request == null) {
            throw badRequest("A reject request body is required.");
        }
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);

        Instant at = Instant.now(clock);
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.REJECT, request.actor(), at);
        decision.setReason(request.reason().trim());
        approval.addDecision(decision);
        approval.setStatus(PolicyApprovalStatus.REJECTED);

        return toDto(repository.saveAndFlush(approval));
    }

    @Transactional
    public PolicyChangeApprovalDto requestChanges(AdminTenantContext context, UUID id,
                                                  RequestChangesRequest request) {
        if (request == null) {
            throw badRequest("A request-changes request body is required.");
        }
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);

        Instant at = Instant.now(clock);
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.REQUEST_CHANGES, request.actor(), at);
        decision.setReason(request.reason().trim());
        approval.addDecision(decision);
        approval.setStatus(PolicyApprovalStatus.IN_REVIEW);

        return toDto(repository.saveAndFlush(approval));
    }

    @Transactional
    public PolicyChangeApprovalDto delegate(AdminTenantContext context, UUID id,
                                            DelegateRequest request) {
        if (request == null) {
            throw badRequest("A delegate request body is required.");
        }
        if (Objects.equals(request.actor().id(), request.delegateTo().id())) {
            throw badRequest("Delegate target must differ from the delegating actor.");
        }
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);

        Instant at = Instant.now(clock);
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.DELEGATE, request.actor(), at);
        decision.setDelegateSubject(request.delegateTo().id().trim());
        decision.setDelegateName(request.delegateTo().name().trim());
        decision.setDelegateRole(request.delegateTo().role().trim());
        decision.setReason(trimToNull(request.reason()));
        approval.addDecision(decision);
        approval.setCurrentApprovers(rewriteApproversForDelegate(
                approval.getCurrentApprovers(), request.actor(), request.delegateTo()));

        return toDto(repository.saveAndFlush(approval));
    }

    @Transactional
    public PolicyChangeApprovalDto attest(AdminTenantContext context, UUID id,
                                          AttestRequest request) {
        if (request == null) {
            throw badRequest("An attest request body is required.");
        }
        PolicyChangeApproval approval = loadRequiredForDecision(context, id);

        Instant at = Instant.now(clock);
        PolicyChangeApprovalDecision decision = baseDecision(
                PolicyApprovalDecisionKind.ATTEST, request.actor(), at);
        decision.setStatement(request.statement().trim());
        decision.setAcceptedAt(request.acceptedAt());
        approval.addDecision(decision);

        return toDto(repository.saveAndFlush(approval));
    }

    private PolicyChangeApproval loadRequired(AdminTenantContext context, UUID id) {
        return repository.findByTenantIdAndId(context.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Policy-change approval request not found."));
    }

    private PolicyChangeApproval loadRequiredForDecision(AdminTenantContext context, UUID id) {
        PolicyChangeApproval approval = loadRequired(context, id);
        if (!OPEN_STATES.contains(approval.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approval request is not open to further decisions.");
        }
        Instant now = Instant.now(clock);
        if (approval.getDeadline() != null && !approval.getDeadline().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approval request has expired and can no longer be decided.");
        }
        return approval;
    }

    private void guardProposerSelfApprove(PolicyChangeApproval approval,
                                          ApprovalActorDto actor) {
        if (Objects.equals(approval.getProposerSubject(), actor.id())) {
            throw new PolicyApprovalProposerSelfException(
                    "Proposer cannot approve their own policy-change request.");
        }
    }

    private PolicyChangeApprovalDecision baseDecision(PolicyApprovalDecisionKind kind,
                                                      ApprovalActorDto actor,
                                                      Instant at) {
        PolicyChangeApprovalDecision decision = new PolicyChangeApprovalDecision();
        decision.setKind(kind);
        decision.setActorSubject(actor.id().trim());
        decision.setActorName(actor.name().trim());
        decision.setActorRole(actor.role().trim());
        decision.setDecidedAt(at);
        return decision;
    }

    private List<String> sanitizeEvidenceRefs(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        List<String> sanitized = new ArrayList<>(refs.size());
        for (String ref : refs) {
            String trimmed = trimToNull(ref);
            if (trimmed != null) {
                sanitized.add(trimmed);
            }
        }
        return sanitized.isEmpty() ? null : sanitized;
    }

    private List<Map<String, Object>> actorListToJson(List<ApprovalActorDto> actors) {
        List<Map<String, Object>> json = new ArrayList<>();
        if (actors == null) {
            return json;
        }
        for (ApprovalActorDto a : actors) {
            json.add(actorToJson(a));
        }
        return json;
    }

    private static Map<String, Object> actorToJson(ApprovalActorDto actor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", actor.id().trim());
        map.put("name", actor.name().trim());
        map.put("role", actor.role().trim());
        return map;
    }

    private List<Map<String, Object>> rewriteApproversForDelegate(
            List<Map<String, Object>> existing,
            ApprovalActorDto delegating,
            ApprovalActorDto delegateTo) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean replaced = false;
        if (existing != null) {
            for (Map<String, Object> a : existing) {
                Object id = a == null ? null : a.get("id");
                if (!replaced && Objects.equals(String.valueOf(id), delegating.id())) {
                    result.add(actorToJson(delegateTo));
                    replaced = true;
                } else {
                    result.add(a);
                }
            }
        }
        if (!replaced) {
            result.add(actorToJson(delegateTo));
        }
        return result;
    }

    private PolicyChangeApprovalDto toDto(PolicyChangeApproval entity) {
        return new PolicyChangeApprovalDto(
                entity.getId(),
                "policy_change",
                entity.getTitle(),
                entity.getTarget(),
                new ApprovalActorDto(entity.getProposerSubject(),
                        entity.getProposerName(), entity.getProposerRole()),
                entity.getReason(),
                entity.getEvidenceRefs() == null
                        ? List.of()
                        : List.copyOf(entity.getEvidenceRefs()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeadline(),
                entity.getStatus(),
                jsonToActorList(entity.getCurrentApprovers()),
                entity.getHistory().stream().map(this::toDecisionDto).toList(),
                entity.getChangeKind(),
                entity.getRiskTier(),
                entity.getBeforeState(),
                entity.getAfterState()
        );
    }

    private static List<ApprovalActorDto> jsonToActorList(List<Map<String, Object>> json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        List<ApprovalActorDto> actors = new ArrayList<>(json.size());
        for (Map<String, Object> a : json) {
            if (a == null) {
                continue;
            }
            actors.add(new ApprovalActorDto(
                    String.valueOf(a.getOrDefault("id", "")),
                    String.valueOf(a.getOrDefault("name", "")),
                    String.valueOf(a.getOrDefault("role", ""))
            ));
        }
        return List.copyOf(actors);
    }

    private DecisionRecordDto toDecisionDto(PolicyChangeApprovalDecision d) {
        ApprovalActorDto actor = new ApprovalActorDto(
                d.getActorSubject(), d.getActorName(), d.getActorRole());
        return switch (d.getKind()) {
            case APPROVE -> new DecisionRecordDto.Approve(actor, d.getDecidedAt(),
                    d.getReason());
            case REJECT -> new DecisionRecordDto.Reject(actor, d.getDecidedAt(),
                    d.getReason());
            case REQUEST_CHANGES -> new DecisionRecordDto.RequestChanges(actor,
                    d.getDecidedAt(), d.getReason());
            case DELEGATE -> new DecisionRecordDto.Delegate(actor,
                    new ApprovalActorDto(d.getDelegateSubject(), d.getDelegateName(),
                            d.getDelegateRole()),
                    d.getDecidedAt(), d.getReason());
            case ATTEST -> new DecisionRecordDto.Attest(actor, d.getDecidedAt(),
                    d.getStatement(), d.getAcceptedAt());
        };
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
