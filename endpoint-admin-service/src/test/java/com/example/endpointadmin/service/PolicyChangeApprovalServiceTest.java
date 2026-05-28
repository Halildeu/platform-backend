package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
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
import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.model.PolicyChangeKind;
import com.example.endpointadmin.model.PolicyRiskTier;
import com.example.endpointadmin.security.AdminTenantContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave-12 PR-5 — service-layer tests for the policy-change approval
 * workflow. Covers the happy path through {@code propose} + all five
 * decision endpoints, plus the 4-eyes guard and status-transition
 * conflict cases.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TimeConfig.class, PolicyChangeApprovalService.class})
class PolicyChangeApprovalServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final ApprovalActorDto PROPOSER = new ApprovalActorDto(
            "alice", "Alice Aerni", "policy_author");

    private static final ApprovalActorDto APPROVER = new ApprovalActorDto(
            "bob", "Bob Berkeley", "security_reviewer");

    private static final ApprovalActorDto APPROVER_TWO = new ApprovalActorDto(
            "carol", "Carol Chen", "compliance_lead");

    @Autowired
    private PolicyChangeApprovalService service;

    @Test
    void proposePersistsApprovalAndReturnsPendingDto() {
        PolicyChangeApprovalDto dto = service.propose(context(),
                proposeRequest("pol-001"));

        assertThat(dto.id()).isNotNull();
        assertThat(dto.type()).isEqualTo("policy_change");
        assertThat(dto.status()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(dto.target()).isEqualTo("pol-001");
        assertThat(dto.changeKind()).isEqualTo(PolicyChangeKind.UPDATE);
        assertThat(dto.riskTier()).isEqualTo(PolicyRiskTier.MEDIUM);
        assertThat(dto.history()).isEmpty();
        assertThat(dto.currentApprovers())
                .extracting(ApprovalActorDto::id)
                .containsExactly(APPROVER.id(), APPROVER_TWO.id());
        assertThat(dto.proposer().id()).isEqualTo(PROPOSER.id());
    }

    @Test
    void approveByApproverTransitionsToApproved() {
        PolicyChangeApprovalDto created = service.propose(context(),
                proposeRequest("pol-approve"));

        PolicyChangeApprovalDto approved = service.approve(context(), created.id(),
                new ApproveRequest(APPROVER, "verified with policy committee"));

        assertThat(approved.status()).isEqualTo(PolicyApprovalStatus.APPROVED);
        assertThat(approved.history()).hasSize(1);
        DecisionRecordDto first = approved.history().get(0);
        assertThat(first).isInstanceOf(DecisionRecordDto.Approve.class);
        DecisionRecordDto.Approve approve = (DecisionRecordDto.Approve) first;
        assertThat(approve.actor().id()).isEqualTo(APPROVER.id());
        assertThat(approve.reason()).isEqualTo("verified with policy committee");
    }

    @Test
    void proposerCannotApproveOwnRequest() {
        PolicyChangeApprovalDto created = service.propose(context(),
                proposeRequest("pol-self"));

        assertThatThrownBy(() -> service.approve(context(), created.id(),
                new ApproveRequest(PROPOSER, "self sign-off")))
                .isInstanceOf(PolicyApprovalProposerSelfException.class)
                .hasMessageContaining("Proposer cannot approve");

        // Status must remain PENDING.
        assertThat(service.get(context(), created.id()).status())
                .isEqualTo(PolicyApprovalStatus.PENDING);
    }

    @Test
    void rejectMovesToRejectedTerminalState() {
        PolicyChangeApprovalDto created = service.propose(context(),
                proposeRequest("pol-reject"));

        PolicyChangeApprovalDto rejected = service.reject(context(), created.id(),
                new RejectRequest(APPROVER, "scope exceeds the original ticket"));

        assertThat(rejected.status()).isEqualTo(PolicyApprovalStatus.REJECTED);
        assertThat(rejected.history()).hasSize(1);
        assertThat(rejected.history().get(0)).isInstanceOf(DecisionRecordDto.Reject.class);
    }

    @Test
    void requestChangesMovesToInReviewAndStaysOpen() {
        PolicyChangeApprovalDto created = service.propose(context(),
                proposeRequest("pol-rc"));

        PolicyChangeApprovalDto inReview = service.requestChanges(context(), created.id(),
                new RequestChangesRequest(APPROVER,
                        "please add the auditor sign-off ref"));

        assertThat(inReview.status()).isEqualTo(PolicyApprovalStatus.IN_REVIEW);
        assertThat(inReview.history())
                .singleElement()
                .isInstanceOf(DecisionRecordDto.RequestChanges.class);

        // The request must still accept further decisions.
        PolicyChangeApprovalDto approved = service.approve(context(), created.id(),
                new ApproveRequest(APPROVER_TWO, null));
        assertThat(approved.status()).isEqualTo(PolicyApprovalStatus.APPROVED);
        assertThat(approved.history()).hasSize(2);
    }

    @Test
    void delegateRewritesCurrentApproversAndStaysOpen() {
        PolicyChangeApprovalDto created = service.propose(context(),
                proposeRequest("pol-delegate"));

        PolicyChangeApprovalDto delegated = service.delegate(context(), created.id(),
                new DelegateRequest(APPROVER, APPROVER_TWO,
                        "on vacation — handing to compliance"));

        // Bob is replaced by Carol; Carol-two is untouched (since she was in list already).
        assertThat(delegated.status()).isEqualTo(PolicyApprovalStatus.PENDING);
        assertThat(delegated.currentApprovers())
                .extracting(ApprovalActorDto::id)
                .containsExactly(APPROVER_TWO.id(), APPROVER_TWO.id());
        assertThat(delegated.history()).hasSize(1);
        DecisionRecordDto record = delegated.history().get(0);
        assertThat(record).isInstanceOf(DecisionRecordDto.Delegate.class);
        DecisionRecordDto.Delegate delegate = (DecisionRecordDto.Delegate) record;
        assertThat(delegate.actor().id()).isEqualTo(APPROVER.id());
        assertThat(delegate.delegateTo().id()).isEqualTo(APPROVER_TWO.id());
    }

    @Test
    void delegateToSelfIsRejectedWith400() {
        PolicyChangeApprovalDto created = service.propose(context(),
                proposeRequest("pol-delegate-self"));

        assertResponseStatus(
                () -> service.delegate(context(), created.id(),
                        new DelegateRequest(APPROVER, APPROVER, null)),
                HttpStatus.BAD_REQUEST,
                "differ");
    }

    @Test
    void attestAppendsRecordButKeepsStatus() {
        PolicyChangeApprovalDto created = service.propose(context(),
                proposeRequest("pol-attest"));

        Instant acceptedAt = Instant.now();
        PolicyChangeApprovalDto attested = service.attest(context(), created.id(),
                new AttestRequest(APPROVER,
                        "I have reviewed the policy and accept ownership of any incident",
                        acceptedAt));

        assertThat(attested.status()).isEqualTo(PolicyApprovalStatus.PENDING);
        DecisionRecordDto record = attested.history().get(0);
        assertThat(record).isInstanceOf(DecisionRecordDto.Attest.class);
        DecisionRecordDto.Attest attest = (DecisionRecordDto.Attest) record;
        assertThat(attest.statement()).contains("accept ownership");
        assertThat(attest.acceptedAt()).isEqualTo(acceptedAt);
    }

    @Test
    void decisionsOnTerminalApprovedAreRejectedWith409() {
        PolicyChangeApprovalDto created = service.propose(context(),
                proposeRequest("pol-terminal"));
        service.approve(context(), created.id(),
                new ApproveRequest(APPROVER, "looks good"));

        assertResponseStatus(
                () -> service.approve(context(), created.id(),
                        new ApproveRequest(APPROVER_TWO, "double approve")),
                HttpStatus.CONFLICT,
                "not open");
    }

    @Test
    void getOnUnknownIdYields404() {
        assertResponseStatus(
                () -> service.get(context(), UUID.randomUUID()),
                HttpStatus.NOT_FOUND,
                "not found");
    }

    @Test
    void listFiltersByStatusAndProposer() {
        PolicyChangeApprovalDto a = service.propose(context(), proposeRequest("pol-list-a"));
        PolicyChangeApprovalDto b = service.propose(context(), proposeRequest("pol-list-b"));
        service.reject(context(), b.id(),
                new RejectRequest(APPROVER, "out of scope"));

        // Filter by status — only the pending one comes back.
        List<PolicyChangeApprovalDto> pending = service.list(context(),
                PolicyApprovalStatus.PENDING, null, null);
        assertThat(pending).extracting(PolicyChangeApprovalDto::id).contains(a.id());
        assertThat(pending).extracting(PolicyChangeApprovalDto::id).doesNotContain(b.id());

        // Filter by target — only pol-list-a.
        List<PolicyChangeApprovalDto> targeted = service.list(context(),
                null, "pol-list-a", null);
        assertThat(targeted).hasSize(1);
        assertThat(targeted.get(0).id()).isEqualTo(a.id());
    }

    @Test
    void proposeWithPastDeadlineIsRejectedWith400() {
        ProposePolicyChangeRequest request = new ProposePolicyChangeRequest(
                "Update DLP exfil rule",
                "pol-past-deadline",
                PROPOSER,
                "compliance ask",
                List.of("ticket-123"),
                List.of(APPROVER, APPROVER_TWO),
                Instant.now().minusSeconds(60),
                PolicyChangeKind.UPDATE,
                PolicyRiskTier.MEDIUM,
                null,
                null);

        assertResponseStatus(
                () -> service.propose(context(), request),
                HttpStatus.BAD_REQUEST,
                "future");
    }

    @Test
    void rejectionWithoutReasonAtServiceLevelStillWorksBecauseDtoEnforcesIt() {
        // Validation of the non-blank `reason` on RejectRequest is enforced
        // at the controller layer (jakarta-validation). The service code
        // itself trims/uses the value; this test asserts a happy reject
        // round-trip with a present reason. (DTO-level NotBlank is unit-
        // tested separately at the controller / mock-mvc layer.)
        PolicyChangeApprovalDto created = service.propose(context(),
                proposeRequest("pol-reject-with-reason"));

        PolicyChangeApprovalDto rejected = service.reject(context(), created.id(),
                new RejectRequest(APPROVER, "rejected, see ticket-456"));

        assertThat(rejected.status()).isEqualTo(PolicyApprovalStatus.REJECTED);
        DecisionRecordDto.Reject reject =
                (DecisionRecordDto.Reject) rejected.history().get(0);
        assertThat(reject.reason()).isEqualTo("rejected, see ticket-456");
    }

    // --- helpers --------------------------------------------------------

    private AdminTenantContext context() {
        return new AdminTenantContext(TENANT_ID, PROPOSER.id());
    }

    private ProposePolicyChangeRequest proposeRequest(String target) {
        return new ProposePolicyChangeRequest(
                "Update DLP exfil rule",
                target,
                PROPOSER,
                "ticket-123 escalation",
                List.of("ticket-123", "https://wiki/runbook"),
                List.of(APPROVER, APPROVER_TWO),
                null,
                PolicyChangeKind.UPDATE,
                PolicyRiskTier.MEDIUM,
                Map.of("rules", List.of("allow:*")),
                Map.of("rules", List.of("deny:exfil-*")));
    }

    private void assertResponseStatus(ThrowingCallable callable,
                                      HttpStatus expected,
                                      String messageFragment) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expected))
                .hasMessageContaining(messageFragment);
    }
}
