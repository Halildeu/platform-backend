package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.PolicyChangeApprovalDto;
import com.example.endpointadmin.dto.v1.admin.ProposePolicyChangeRequest;
import com.example.endpointadmin.dto.v1.admin.RemoteViewPolicyProposalRequest;
import com.example.endpointadmin.dto.v1.admin.RemoteViewPolicyPublicationDto;
import com.example.endpointadmin.dto.v1.admin.RemoteViewPolicyRevocationDto;
import com.example.endpointadmin.dto.v1.admin.RemoteViewPolicyRevocationProposalRequest;
import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.model.PolicyChangeApproval;
import com.example.endpointadmin.model.PolicyChangeKind;
import com.example.endpointadmin.model.RemoteViewPolicyApprovalIntake;
import com.example.endpointadmin.model.RemoteViewPolicyPublication;
import com.example.endpointadmin.model.RemoteViewPolicyRevocation;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyException;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyReason;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyValidator;
import com.example.endpointadmin.remoteaccess.policy.ValidatedRemoteViewPolicy;
import com.example.endpointadmin.repository.PolicyChangeApprovalRepository;
import com.example.endpointadmin.repository.RemoteViewPolicyApprovalIntakeRepository;
import com.example.endpointadmin.repository.RemoteViewPolicyPublicationRepository;
import com.example.endpointadmin.repository.RemoteViewPolicyRevocationRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict proposal provenance and approval-id-only immutable publication. */
@Service
@ConditionalOnProperty(prefix = "remote-view-policy", name = "enabled", havingValue = "true")
public class RemoteViewPolicyWorkflowService {
    private static final Set<String> PROPOSAL_FIELDS = Set.of(
            "title", "proposer", "reason", "evidenceRefs", "currentApprovers", "deadline",
            "changeKind", "riskTier", "before", "policy");
    private static final Set<String> REVOCATION_PROPOSAL_FIELDS = Set.of(
            "publicationId", "title", "proposer", "reason", "evidenceRefs", "currentApprovers",
            "deadline", "riskTier");

    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final RemoteViewPolicyValidator policyValidator;
    private final PolicyChangeApprovalService approvalService;
    private final PolicyChangeApprovalRepository approvalRepository;
    private final RemoteViewPolicyApprovalIntakeRepository intakeRepository;
    private final RemoteViewPolicyPublicationRepository publicationRepository;
    private final RemoteViewPolicyRevocationRepository revocationRepository;
    private final Validator beanValidator;
    private final Clock clock;

    public RemoteViewPolicyWorkflowService(RemoteViewJsonCanonicalizer canonicalizer,
                                           RemoteViewPolicyValidator policyValidator,
                                           PolicyChangeApprovalService approvalService,
                                           PolicyChangeApprovalRepository approvalRepository,
                                           RemoteViewPolicyApprovalIntakeRepository intakeRepository,
                                           RemoteViewPolicyPublicationRepository publicationRepository,
                                           RemoteViewPolicyRevocationRepository revocationRepository,
                                           Validator beanValidator, Clock clock) {
        this.canonicalizer = canonicalizer;
        this.policyValidator = policyValidator;
        this.approvalService = approvalService;
        this.approvalRepository = approvalRepository;
        this.intakeRepository = intakeRepository;
        this.publicationRepository = publicationRepository;
        this.revocationRepository = revocationRepository;
        this.beanValidator = beanValidator;
        this.clock = clock;
    }

    @Transactional
    public PolicyChangeApprovalDto propose(AdminTenantContext context, String rawRequest) {
        JsonNode root = canonicalizer.strictParse(rawRequest);
        if (!root.isObject()) {
            throw badRequest("Remote-view policy proposal must be a JSON object");
        }
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!PROPOSAL_FIELDS.contains(field)) {
                throw badRequest("Unknown remote-view policy proposal field: " + field);
            }
        }
        RemoteViewPolicyProposalRequest request;
        try {
            request = canonicalizer.mapper().treeToValue(root, RemoteViewPolicyProposalRequest.class);
        } catch (JsonProcessingException e) {
            throw badRequest("Remote-view policy proposal metadata is invalid");
        }
        Set<ConstraintViolation<RemoteViewPolicyProposalRequest>> violations = beanValidator.validate(request);
        if (!violations.isEmpty()) {
            throw badRequest("Remote-view policy proposal metadata is incomplete");
        }
        Instant now = Instant.now(clock);
        ValidatedRemoteViewPolicy validated = policyValidator.validateForPublication(
                request.policy(), context.tenantId(), now);
        String canonicalSource = canonicalizer.canonicalString(request.policy());

        Map<String, Object> after = canonicalizer.mapper().convertValue(
                request.policy(), new TypeReference<>() { });
        ProposePolicyChangeRequest generic = new ProposePolicyChangeRequest(
                request.title(), validated.policyId(), request.proposer(), request.reason(), request.evidenceRefs(),
                request.currentApprovers(), request.deadline(), request.changeKind(), request.riskTier(),
                request.before(), after);
        PolicyChangeApprovalDto approval = approvalService.propose(context, generic);
        try {
            intakeRepository.saveAndFlush(new RemoteViewPolicyApprovalIntake(
                    approval.id(), context.tenantId(), validated.policyId(), validated.policyVersion(),
                    canonicalSource, validated.policyDigest(), context.subject(), now));
        } catch (DataIntegrityViolationException duplicate) {
            if (hasConstraint(duplicate, "uq_rv_policy_intake_tenant_identity")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This tenant policy id and version already has an intake", duplicate);
            }
            throw duplicate;
        }
        return approval;
    }

    @Transactional
    public RemoteViewPolicyPublicationDto publish(AdminTenantContext context, String rawRequest) {
        return publish(context, approvalIdOnly(rawRequest));
    }

    @Transactional
    public RemoteViewPolicyPublicationDto publish(AdminTenantContext context, UUID approvalId) {
        if (approvalId == null) {
            throw badRequest("approvalId is required");
        }
        PolicyChangeApproval approval = approvalRepository
                .findByTenantIdAndIdForUpdate(context.tenantId(), approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Remote-view policy approval not found"));
        RemoteViewPolicyApprovalIntake intake = intakeRepository
                .findByTenantIdAndApprovalId(context.tenantId(), approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Approval did not cross the strict remote-view policy intake"));
        RemoteViewPolicyPublication existing = publicationRepository
                .findByTenantIdAndApprovalId(context.tenantId(), approvalId).orElse(null);
        if (existing != null) {
            return toDto(existing);
        }
        Instant now = Instant.now(clock);
        requireUsableApproval(approval, now);
        if (!intake.getPolicyId().equals(approval.getTarget())) {
            throw invalid("Approval target does not match the strict intake");
        }
        JsonNode source = canonicalizer.parseCanonical(intake.getCanonicalSource());
        String approvedDigest = canonicalizer.digest(
                canonicalizer.mapper().valueToTree(approval.getAfterState()));
        if (!intake.getPolicyDigest().equals(approvedDigest)
                || !intake.getPolicyDigest().equals(canonicalizer.digest(source))) {
            throw invalid("Approved policy value does not match its strict intake provenance");
        }
        ValidatedRemoteViewPolicy validated = policyValidator.validateForPublication(
                source, context.tenantId(), now);
        if (!validated.policyId().equals(intake.getPolicyId())
                || !validated.policyVersion().equals(intake.getPolicyVersion())) {
            throw invalid("Policy identity changed after strict intake");
        }
        String supersedesDigest = nullableText(source.path("lifecycle").get("supersedesPolicyDigest"));
        RemoteViewPolicyPublication latest = publicationRepository.findLatest(context.tenantId()).orElse(null);
        validatePublicationTransition(approval, latest, supersedesDigest, validated);

        RemoteViewPolicyPublication publication = new RemoteViewPolicyPublication(
                UUID.randomUUID(), approvalId, context.tenantId(), validated.policyId(), validated.policyVersion(),
                validated.deploymentClass(), intake.getCanonicalSource(), validated.policyDigest(),
                validated.baselineDigest(), validated.legalEvidenceDigest(), validated.legalEvidenceStatus(),
                supersedesDigest,
                validated.validFrom(), validated.validUntil(), validated.reviewBy(), validated.legalReviewBy(),
                context.subject(), now);
        try {
            return toDto(publicationRepository.saveAndFlush(publication));
        } catch (DataIntegrityViolationException race) {
            if (hasConstraint(race, "ux_rv_policy_publication_genesis")
                    || hasConstraint(race, "ux_rv_policy_publication_successor")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Policy publication chain advanced concurrently; create a new approval", race);
            }
            throw race;
        }
    }

    @Transactional
    public PolicyChangeApprovalDto proposeRevocation(AdminTenantContext context, String rawRequest) {
        JsonNode root = canonicalizer.strictParse(rawRequest);
        if (!root.isObject()) {
            throw badRequest("Remote-view policy revocation proposal must be a JSON object");
        }
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!REVOCATION_PROPOSAL_FIELDS.contains(field)) {
                throw badRequest("Unknown remote-view policy revocation proposal field: " + field);
            }
        }
        RemoteViewPolicyRevocationProposalRequest request;
        try {
            request = canonicalizer.mapper().treeToValue(root, RemoteViewPolicyRevocationProposalRequest.class);
        } catch (JsonProcessingException e) {
            throw badRequest("Remote-view policy revocation proposal metadata is invalid");
        }
        Set<ConstraintViolation<RemoteViewPolicyRevocationProposalRequest>> violations =
                beanValidator.validate(request);
        if (!violations.isEmpty()) {
            throw badRequest("Remote-view policy revocation proposal metadata is incomplete");
        }
        return proposeRevocation(context, request);
    }

    @Transactional
    public PolicyChangeApprovalDto proposeRevocation(AdminTenantContext context,
                                                      RemoteViewPolicyRevocationProposalRequest request) {
        RemoteViewPolicyPublication publication = publicationRepository
                .findByTenantIdAndId(context.tenantId(), request.publicationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Remote-view policy publication not found"));
        if (revocationRepository.findByTenantIdAndPublicationId(context.tenantId(), publication.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Policy publication is already revoked");
        }
        Map<String, Object> before = canonicalizer.mapper().convertValue(
                canonicalizer.parseCanonical(publication.getCanonicalSource()), new TypeReference<>() { });
        ProposePolicyChangeRequest generic = new ProposePolicyChangeRequest(
                request.title(), publication.getPolicyId(), request.proposer(), request.reason(),
                request.evidenceRefs(), request.currentApprovers(), request.deadline(), PolicyChangeKind.DELETE,
                request.riskTier(), before, null);
        return approvalService.propose(context, generic);
    }

    @Transactional
    public RemoteViewPolicyRevocationDto revoke(AdminTenantContext context, String rawRequest) {
        return revoke(context, approvalIdOnly(rawRequest));
    }

    @Transactional
    public RemoteViewPolicyRevocationDto revoke(AdminTenantContext context, UUID approvalId) {
        if (approvalId == null) {
            throw badRequest("approvalId is required");
        }
        PolicyChangeApproval approval = approvalRepository
                .findByTenantIdAndIdForUpdate(context.tenantId(), approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Remote-view policy revocation approval not found"));
        RemoteViewPolicyRevocation existing = revocationRepository
                .findByTenantIdAndApprovalId(context.tenantId(), approvalId).orElse(null);
        if (existing != null) {
            return toDto(existing);
        }
        Instant now = Instant.now(clock);
        requireUsableApproval(approval, now);
        if (approval.getChangeKind() != PolicyChangeKind.DELETE
                || (approval.getAfterState() != null && !approval.getAfterState().isEmpty())
                || approval.getBeforeState() == null || approval.getBeforeState().isEmpty()) {
            throw invalid("Revocation approval must be an exact DELETE transition");
        }
        String approvedPolicyDigest = canonicalizer.digest(
                canonicalizer.mapper().valueToTree(approval.getBeforeState()));
        RemoteViewPolicyPublication publication = publicationRepository
                .findByTenantIdAndPolicyDigest(context.tenantId(), approvedPolicyDigest)
                .orElseThrow(() -> invalid("Revocation approval is not bound to a published policy"));
        if (!publication.getPolicyId().equals(approval.getTarget())) {
            throw invalid("Revocation approval target does not match the published policy");
        }
        if (revocationRepository.findByTenantIdAndPublicationId(context.tenantId(), publication.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Policy publication is already revoked");
        }
        String revocationReason = approval.getReason();
        if (revocationReason == null || revocationReason.isBlank()) {
            throw invalid("Revocation approval reason is missing");
        }
        RemoteViewPolicyRevocation revocation = new RemoteViewPolicyRevocation(
                UUID.randomUUID(), publication.getId(), approvalId, context.tenantId(), publication.getPolicyDigest(),
                revocationReason.trim(), context.subject(), now);
        try {
            return toDto(revocationRepository.saveAndFlush(revocation));
        } catch (DataIntegrityViolationException race) {
            if (hasConstraint(race, "uq_rv_policy_revocation_publication")
                    || hasConstraint(race, "uq_rv_policy_revocation_approval")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Policy publication was revoked concurrently", race);
            }
            throw race;
        }
    }

    private static RemoteViewPolicyPublicationDto toDto(RemoteViewPolicyPublication p) {
        return new RemoteViewPolicyPublicationDto(p.getId(), p.getApprovalId(), p.getTenantId(), p.getPolicyId(),
                p.getPolicyVersion(), p.getDeploymentClass(), p.getPolicyDigest(), p.getBaselineDigest(),
                p.getLegalEvidenceDigest(), p.getLegalEvidenceStatus(), p.getSupersedesPolicyDigest(),
                p.getValidFrom(), p.getValidUntil(),
                p.getReviewBy(), p.getLegalReviewBy(), p.getPublishedBySubject(), p.getPublishedAt());
    }

    private static RemoteViewPolicyRevocationDto toDto(RemoteViewPolicyRevocation r) {
        return new RemoteViewPolicyRevocationDto(r.getId(), r.getPublicationId(), r.getApprovalId(), r.getTenantId(),
                r.getPolicyDigest(), r.getReason(), r.getRevokedBySubject(), r.getRevokedAt());
    }

    private void validatePublicationTransition(PolicyChangeApproval approval, RemoteViewPolicyPublication latest,
                                               String supersedesDigest, ValidatedRemoteViewPolicy candidate) {
        if (latest == null) {
            if (approval.getChangeKind() != PolicyChangeKind.CREATE || supersedesDigest != null
                    || (approval.getBeforeState() != null && !approval.getBeforeState().isEmpty())) {
                throw invalid("First publication must be a CREATE with no predecessor");
            }
            return;
        }
        if (approval.getChangeKind() != PolicyChangeKind.UPDATE
                || !latest.getPolicyDigest().equals(supersedesDigest)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Policy update does not supersede the current publication head");
        }
        if (approval.getBeforeState() == null || !latest.getPolicyDigest().equals(canonicalizer.digest(
                canonicalizer.mapper().valueToTree(approval.getBeforeState())))) {
            throw invalid("Policy update approval is not bound to the current publication head");
        }
        if (candidate.validFrom().isBefore(latest.getValidFrom())) {
            throw invalid("Successor validFrom cannot precede its predecessor");
        }
    }

    private static void requireUsableApproval(PolicyChangeApproval approval, Instant now) {
        if (approval.getStatus() != PolicyApprovalStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval is not approved");
        }
        if (approval.getDeadline() != null && !approval.getDeadline().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval has expired");
        }
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static boolean hasConstraint(DataIntegrityViolationException exception, String constraint) {
        Throwable cursor = exception;
        while (cursor != null) {
            if (cursor.getMessage() != null && cursor.getMessage().contains(constraint)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private UUID approvalIdOnly(String rawRequest) {
        JsonNode root = canonicalizer.strictParse(rawRequest);
        if (!root.isObject() || root.size() != 1 || !root.has("approvalId")
                || !root.path("approvalId").isTextual()) {
            throw badRequest("Request must contain approvalId only");
        }
        try {
            String raw = root.path("approvalId").textValue();
            UUID parsed = UUID.fromString(raw);
            if (!parsed.toString().equals(raw)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw badRequest("approvalId must be a canonical UUID");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static RemoteViewPolicyException invalid(String message) {
        return new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_INVALID, message);
    }
}
