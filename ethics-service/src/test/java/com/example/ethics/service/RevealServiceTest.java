package com.example.ethics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.api.RevealDtos.RevealSubmitRequest;
import com.example.ethics.model.EthicsCase;
import com.example.ethics.model.EthicsMessage;
import com.example.ethics.model.EthicsReport;
import com.example.ethics.model.RevealAuditLog;
import com.example.ethics.model.RevealRequest;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.EthicsMessageRepository;
import com.example.ethics.repository.EthicsReportRepository;
import com.example.ethics.repository.RevealAuditLogRepository;
import com.example.ethics.repository.RevealRequestRepository;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.StaffContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RevealServiceTest {
    @Mock EthicsCaseRepository cases;
    @Mock EthicsReportRepository reports;
    @Mock EthicsMessageRepository messages;
    @Mock RevealRequestRepository revealRequests;
    @Mock RevealAuditLogRepository revealAudit;
    @Mock EthicsAuthorization authorization;
    RevealService service;

    UUID orgId;
    UUID caseId;
    EthicsCase caseRow;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        caseId = UUID.randomUUID();
        caseRow = new EthicsCase(caseId, orgId, Instant.now());
        service = new RevealService(cases, reports, messages, revealRequests, revealAudit,
                authorization, new ObjectMapper());
    }

    @Test
    void submitRequiresRecognisedLegalBasis() {
        StaffContext staff = new StaffContext(orgId, "sub-officer");
        when(cases.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.of(caseRow));
        when(authorization.can(staff, "case_viewer", caseId)).thenReturn(true);
        assertThatThrownBy(() -> service.submit(staff, new RevealSubmitRequest(caseId, "Prosecutor",
                "MADE_UP_BASIS", "Court", "12345/2026", "Justification")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void approvalByRequesterIsRejected() {
        StaffContext requester = new StaffContext(orgId, "sub-officer");
        RevealRequest existing = new RevealRequest(UUID.randomUUID(), caseId,
                requester.subject(), "Prosecutor",
                "KVKK_MD28", "Court", "12345/2026", "Justification", Instant.now());
        when(revealRequests.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(cases.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.of(caseRow));
        when(authorization.can(requester, "case_handler", caseId)).thenReturn(true);
        assertThatThrownBy(() -> service.approve(requester, existing.getId(), "Requester", "requester"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void twoDistinctApproversAdvanceStatusToReady() {
        StaffContext requester = new StaffContext(orgId, "sub-officer");
        RevealRequest existing = new RevealRequest(UUID.randomUUID(), caseId,
                requester.subject(), "Prosecutor",
                "KVKK_MD28", "Court", "12345/2026", "Justification", Instant.now());
        StaffContext firstApprover = new StaffContext(orgId, "sub-legal-1");
        StaffContext secondApprover = new StaffContext(orgId, "sub-legal-2");
        when(revealRequests.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(cases.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.of(caseRow));
        when(authorization.can(any(StaffContext.class), eq("case_handler"), eq(caseId))).thenReturn(true);
        when(revealRequests.save(any(RevealRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        service.approve(firstApprover, existing.getId(), "Legal Officer 1", "reveal_officer");
        assertThat(existing.getStatus()).isEqualTo("ONE_APPROVED");
        service.approve(secondApprover, existing.getId(), "Legal Officer 2", "reveal_officer");
        assertThat(existing.getStatus()).isEqualTo("READY");
        verify(revealAudit, times(2)).save(any(RevealAuditLog.class));
    }

    @Test
    void sameApproverTwiceViolatesTwoPersonRule() {
        StaffContext requester = new StaffContext(orgId, "sub-officer");
        StaffContext approver = new StaffContext(orgId, "sub-legal-1");
        RevealRequest existing = new RevealRequest(UUID.randomUUID(), caseId,
                requester.subject(), "Prosecutor",
                "KVKK_MD28", "Court", "12345/2026", "Justification", Instant.now());
        existing.recordFirstApproval(approver.subject(), "Legal", "reveal_officer", Instant.now());
        when(revealRequests.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(cases.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.of(caseRow));
        when(authorization.can(approver, "case_handler", caseId)).thenReturn(true);
        assertThatThrownBy(() -> service.approve(approver, existing.getId(), "Legal", "reveal_officer"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void executeRequiresReadyStatusAndDeliversCaseSnapshotPlusWormAudit() {
        StaffContext requester = new StaffContext(orgId, "sub-officer");
        StaffContext executor = new StaffContext(orgId, "sub-exec");
        RevealRequest existing = new RevealRequest(UUID.randomUUID(), caseId,
                requester.subject(), "Prosecutor",
                "KVKK_MD28", "Court", "12345/2026", "Justification", Instant.now());
        existing.recordFirstApproval("sub-legal-1", "Legal 1", "reveal_officer", Instant.now());
        existing.recordSecondApproval("sub-legal-2", "Legal 2", "reveal_officer", Instant.now());

        when(revealRequests.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(cases.findByIdAndOrgId(caseId, orgId)).thenReturn(Optional.of(caseRow));
        when(authorization.can(executor, "case_handler", caseId)).thenReturn(true);
        when(cases.findById(caseId)).thenReturn(Optional.of(caseRow));
        when(reports.findByCaseId(caseId)).thenReturn(Optional.of(new EthicsReport(
                UUID.randomUUID(), caseId, "ANONYMOUS", "OTHER", "Subject",
                "Narrative body", "tr-TR", "v1.0.0", Instant.now())));
        when(messages.findAllByCaseIdAndVisibilityInOrderByCreatedAtAsc(eq(caseId), any()))
                .thenReturn(List.<EthicsMessage>of());
        when(revealRequests.save(any(RevealRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        var payload = service.execute(executor, existing.getId());
        assertThat(payload.subject()).isEqualTo("Subject");
        assertThat(payload.description()).isEqualTo("Narrative body");
        assertThat(existing.getStatus()).isEqualTo("EXECUTED");
        // Two audit rows: REQUEST_EXECUTED + ACCESS_RETRIEVED
        verify(revealAudit, times(2)).save(any(RevealAuditLog.class));
    }
}
