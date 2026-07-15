package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.PolicyChangeApprovalDto;
import com.example.endpointadmin.dto.v1.admin.ApprovalActorDto;
import com.example.endpointadmin.dto.v1.admin.ProposePolicyChangeRequest;
import com.example.endpointadmin.dto.v1.admin.RemoteViewPolicyRevocationProposalRequest;
import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.model.PolicyChangeApproval;
import com.example.endpointadmin.model.PolicyChangeKind;
import com.example.endpointadmin.model.PolicyRiskTier;
import com.example.endpointadmin.model.RemoteViewPolicyApprovalIntake;
import com.example.endpointadmin.model.RemoteViewPolicyPublication;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyArtifacts;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyException;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyProperties;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyTestSupport;
import com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyValidator;
import com.example.endpointadmin.repository.PolicyChangeApprovalRepository;
import com.example.endpointadmin.repository.RemoteViewPolicyApprovalIntakeRepository;
import com.example.endpointadmin.repository.RemoteViewPolicyPublicationRepository;
import com.example.endpointadmin.repository.RemoteViewPolicyRevocationRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteViewPolicyWorkflowServiceTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000245");
    private static final UUID APPROVAL_ID = UUID.fromString("00000000-0000-4000-8000-000000000838");
    private static final AdminTenantContext CONTEXT = new AdminTenantContext(TENANT, "proposer-1");

    private RemoteViewJsonCanonicalizer canonicalizer;
    private JsonNode policy;
    private PolicyChangeApprovalService approvals;
    private PolicyChangeApprovalRepository approvalRepository;
    private RemoteViewPolicyApprovalIntakeRepository intakes;
    private RemoteViewPolicyPublicationRepository publications;
    private RemoteViewPolicyRevocationRepository revocations;
    private RemoteViewPolicyWorkflowService service;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        canonicalizer = new RemoteViewJsonCanonicalizer();
        var pair = RemoteViewPolicyTestSupport.generateKeyPair();
        Path privateKey = temp.resolve("private.der");
        Path publicKey = temp.resolve("public.der");
        Files.write(privateKey, pair.getPrivate().getEncoded());
        Files.write(publicKey, pair.getPublic().getEncoded());
        RemoteViewPolicyProperties properties = RemoteViewPolicyTestSupport.properties(privateKey, publicKey);
        RemoteViewPolicyValidator validator = new RemoteViewPolicyValidator(canonicalizer,
                RemoteViewPolicyArtifacts.load(properties, canonicalizer));
        policy = canonicalizer.strictParse(Files.readString(
                RemoteViewPolicyTestSupport.fixture("example-policy.json")));
        approvals = mock(PolicyChangeApprovalService.class);
        approvalRepository = mock(PolicyChangeApprovalRepository.class);
        intakes = mock(RemoteViewPolicyApprovalIntakeRepository.class);
        publications = mock(RemoteViewPolicyPublicationRepository.class);
        revocations = mock(RemoteViewPolicyRevocationRepository.class);
        service = new RemoteViewPolicyWorkflowService(canonicalizer, validator, approvals, approvalRepository,
                intakes, publications, revocations, Validation.buildDefaultValidatorFactory().getValidator(),
                RemoteViewPolicyTestSupport.CLOCK);
    }

    @Test
    void dedicatedProposalCreatesStrictImmutableProvenance() {
        PolicyChangeApprovalDto approval = mock(PolicyChangeApprovalDto.class);
        when(approval.id()).thenReturn(APPROVAL_ID);
        when(approvals.propose(any(), any())).thenReturn(approval);
        service.propose(CONTEXT, proposalJson());

        ArgumentCaptor<RemoteViewPolicyApprovalIntake> captor =
                ArgumentCaptor.forClass(RemoteViewPolicyApprovalIntake.class);
        verify(intakes).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getApprovalId()).isEqualTo(APPROVAL_ID);
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(captor.getValue().getPolicyDigest())
                .isEqualTo("sha256:932397b4844474922392324a00c84457db026a5d00de837f1fd2daf8985c86d4");
    }

    @Test
    void dedicatedProposalRejectsDuplicateKeysBeforeGenericApprovalBinding() {
        String valid = proposalJson();
        String duplicate = valid.replaceFirst("\\{", "{\"title\":\"duplicate\",");
        assertThatThrownBy(() -> service.propose(CONTEXT, duplicate))
                .isInstanceOf(RemoteViewPolicyException.class);
    }

    @Test
    void duplicatePolicyIdentityRollsBackAndReturnsConflict() {
        PolicyChangeApprovalDto approval = mock(PolicyChangeApprovalDto.class);
        when(approval.id()).thenReturn(APPROVAL_ID);
        when(approvals.propose(any(), any())).thenReturn(approval);
        when(intakes.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "constraint uq_rv_policy_intake_tenant_identity"));

        assertThatThrownBy(() -> service.propose(CONTEXT, proposalJson()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("already has an intake");
    }

    @Test
    void publicationAndRevocationRejectCallerSuppliedFieldsBeyondApprovalId() {
        String smuggled = "{\"approvalId\":\"" + APPROVAL_ID + "\",\"policy\":{}}";
        assertThatThrownBy(() -> service.publish(CONTEXT, smuggled))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("approvalId only");
        assertThatThrownBy(() -> service.revoke(CONTEXT, smuggled))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("approvalId only");
        assertThatThrownBy(() -> service.publish(CONTEXT, "{\"approvalId\":\"1-1-1-1-1\"}"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("canonical UUID");
    }

    @Test
    void publishAcceptsApprovalIdOnlyAndRevalidatesApprovedValue() {
        String canonical = canonicalizer.canonicalString(policy);
        PolicyChangeApproval approval = approved(canonicalizer.mapper().convertValue(policy,
                new TypeReference<Map<String, Object>>() { }));
        RemoteViewPolicyApprovalIntake intake = new RemoteViewPolicyApprovalIntake(APPROVAL_ID, TENANT,
                "example-tr-domestic-view-only", "1.0.0", canonical,
                "sha256:932397b4844474922392324a00c84457db026a5d00de837f1fd2daf8985c86d4",
                "proposer-1", RemoteViewPolicyTestSupport.NOW);
        when(approvalRepository.findByTenantIdAndIdForUpdate(TENANT, APPROVAL_ID))
                .thenReturn(Optional.of(approval));
        when(intakes.findByTenantIdAndApprovalId(TENANT, APPROVAL_ID)).thenReturn(Optional.of(intake));
        when(publications.findByTenantIdAndApprovalId(TENANT, APPROVAL_ID)).thenReturn(Optional.empty());
        when(publications.findLatest(TENANT)).thenReturn(Optional.empty());
        when(publications.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.publish(CONTEXT, APPROVAL_ID);
        assertThat(result.approvalId()).isEqualTo(APPROVAL_ID);
        assertThat(result.tenantId()).isEqualTo(TENANT);
        assertThat(result.policyDigest()).isEqualTo(intake.getPolicyDigest());
    }

    @Test
    void publishRejectsApprovalValueDriftAfterStrictIntake() {
        String canonical = canonicalizer.canonicalString(policy);
        Map<String, Object> changed = canonicalizer.mapper().convertValue(policy,
                new TypeReference<Map<String, Object>>() { });
        changed.put("policyVersion", "2.0.0");
        when(approvalRepository.findByTenantIdAndIdForUpdate(TENANT, APPROVAL_ID))
                .thenReturn(Optional.of(approved(changed)));
        when(intakes.findByTenantIdAndApprovalId(TENANT, APPROVAL_ID)).thenReturn(Optional.of(
                new RemoteViewPolicyApprovalIntake(APPROVAL_ID, TENANT, "example-tr-domestic-view-only", "1.0.0",
                        canonical, "sha256:932397b4844474922392324a00c84457db026a5d00de837f1fd2daf8985c86d4",
                        "proposer-1", RemoteViewPolicyTestSupport.NOW)));
        when(publications.findByTenantIdAndApprovalId(TENANT, APPROVAL_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.publish(CONTEXT, APPROVAL_ID))
                .isInstanceOf(RemoteViewPolicyException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void publicationCannotForkTheCurrentContentAddressedHead() {
        String canonical = canonicalizer.canonicalString(policy);
        PolicyChangeApproval approval = approved(canonicalizer.mapper().convertValue(policy,
                new TypeReference<Map<String, Object>>() { }));
        approval.setChangeKind(PolicyChangeKind.UPDATE);
        approval.setBeforeState(canonicalizer.mapper().convertValue(policy, new TypeReference<>() { }));
        RemoteViewPolicyApprovalIntake intake = new RemoteViewPolicyApprovalIntake(APPROVAL_ID, TENANT,
                "example-tr-domestic-view-only", "1.0.0", canonical,
                "sha256:932397b4844474922392324a00c84457db026a5d00de837f1fd2daf8985c86d4",
                "proposer-1", RemoteViewPolicyTestSupport.NOW);
        when(approvalRepository.findByTenantIdAndIdForUpdate(TENANT, APPROVAL_ID))
                .thenReturn(Optional.of(approval));
        when(intakes.findByTenantIdAndApprovalId(TENANT, APPROVAL_ID)).thenReturn(Optional.of(intake));
        when(publications.findByTenantIdAndApprovalId(TENANT, APPROVAL_ID)).thenReturn(Optional.empty());
        when(publications.findLatest(TENANT)).thenReturn(Optional.of(publication()));

        assertThatThrownBy(() -> service.publish(CONTEXT, APPROVAL_ID))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("does not supersede");
    }

    @Test
    void revocationProposalBindsDeleteApprovalToExactPublishedSource() {
        RemoteViewPolicyPublication publication = publication();
        when(publications.findByTenantIdAndId(TENANT, publication.getId())).thenReturn(Optional.of(publication));
        when(revocations.findByTenantIdAndPublicationId(TENANT, publication.getId())).thenReturn(Optional.empty());
        PolicyChangeApprovalDto approval = mock(PolicyChangeApprovalDto.class);
        when(approvals.propose(any(), any())).thenReturn(approval);
        var request = new RemoteViewPolicyRevocationProposalRequest(publication.getId(), "Revoke policy",
                new ApprovalActorDto("proposer-1", "Proposer One", "privacy-owner"),
                "Legal evidence was withdrawn", List.of("issue:2451"),
                List.of(new ApprovalActorDto("approver-2", "Approver Two", "legal-reviewer")),
                Instant.parse("2026-08-01T00:00:00Z"), PolicyRiskTier.HIGH);

        service.proposeRevocation(CONTEXT, request);

        ArgumentCaptor<ProposePolicyChangeRequest> captor = ArgumentCaptor.forClass(ProposePolicyChangeRequest.class);
        verify(approvals).propose(org.mockito.ArgumentMatchers.eq(CONTEXT), captor.capture());
        assertThat(captor.getValue().changeKind()).isEqualTo(PolicyChangeKind.DELETE);
        assertThat(captor.getValue().after()).isNull();
        assertThat(canonicalizer.digest(canonicalizer.mapper().valueToTree(captor.getValue().before())))
                .isEqualTo(publication.getPolicyDigest());
    }

    @Test
    void approvedDeleteAppendsRevocationDerivedOnlyFromApproval() {
        RemoteViewPolicyPublication publication = publication();
        Map<String, Object> before = canonicalizer.mapper().convertValue(policy, new TypeReference<>() { });
        PolicyChangeApproval approval = approved(null);
        approval.setBeforeState(before);
        approval.setChangeKind(PolicyChangeKind.DELETE);
        approval.setReason("Legal evidence was withdrawn");
        when(approvalRepository.findByTenantIdAndIdForUpdate(TENANT, APPROVAL_ID))
                .thenReturn(Optional.of(approval));
        when(revocations.findByTenantIdAndApprovalId(TENANT, APPROVAL_ID)).thenReturn(Optional.empty());
        when(publications.findByTenantIdAndPolicyDigest(TENANT, publication.getPolicyDigest()))
                .thenReturn(Optional.of(publication));
        when(revocations.findByTenantIdAndPublicationId(TENANT, publication.getId())).thenReturn(Optional.empty());
        when(revocations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.revoke(CONTEXT, APPROVAL_ID);

        assertThat(result.publicationId()).isEqualTo(publication.getId());
        assertThat(result.policyDigest()).isEqualTo(publication.getPolicyDigest());
        assertThat(result.reason()).isEqualTo("Legal evidence was withdrawn");
    }

    private PolicyChangeApproval approved(Map<String, Object> after) {
        PolicyChangeApproval approval = new PolicyChangeApproval();
        approval.setId(APPROVAL_ID);
        approval.setTenantId(TENANT);
        approval.setTarget("example-tr-domestic-view-only");
        approval.setAfterState(after);
        approval.setChangeKind(PolicyChangeKind.CREATE);
        approval.setStatus(PolicyApprovalStatus.APPROVED);
        approval.setDeadline(Instant.parse("2026-08-01T00:00:00Z"));
        return approval;
    }

    private RemoteViewPolicyPublication publication() {
        String canonical = canonicalizer.canonicalString(policy);
        return new RemoteViewPolicyPublication(UUID.fromString("00000000-0000-4000-8000-000000000839"),
                UUID.fromString("00000000-0000-4000-8000-000000000837"), TENANT,
                "example-tr-domestic-view-only", "1.0.0", "bounded-test", canonical,
                "sha256:932397b4844474922392324a00c84457db026a5d00de837f1fd2daf8985c86d4",
                "sha256:54132a1b4d035db7011f1ce200433234aea7fe0d04420f0928dcf26a06386337",
                "sha256:41fe3b5dc79dee11062ef9708de454ee78117cdcde55de427e8c301cf7cd609b",
                "tracked-pending", null, Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"), "publisher", RemoteViewPolicyTestSupport.NOW);
    }

    private String proposalJson() {
        ObjectNode root = canonicalizer.mapper().createObjectNode();
        root.put("title", "Remote VIEW_ONLY tenant policy");
        ObjectNode proposer = root.putObject("proposer");
        proposer.put("id", "proposer-1");
        proposer.put("name", "Proposer One");
        proposer.put("role", "privacy-owner");
        root.put("reason", "Publish a reviewed tenant privacy policy");
        ArrayNode approvers = root.putArray("currentApprovers");
        ObjectNode approver = approvers.addObject();
        approver.put("id", "approver-2");
        approver.put("name", "Approver Two");
        approver.put("role", "legal-reviewer");
        root.put("deadline", "2026-08-01T00:00:00Z");
        root.put("changeKind", "CREATE");
        root.put("riskTier", "HIGH");
        root.set("policy", policy);
        return canonicalizer.canonicalString(root);
    }
}
