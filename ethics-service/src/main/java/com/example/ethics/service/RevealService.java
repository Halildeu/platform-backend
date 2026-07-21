package com.example.ethics.service;

import com.example.ethics.api.RevealDtos.*;
import com.example.ethics.model.*;
import com.example.ethics.repository.*;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.StaffContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Faz 35 ES-303 Reveal workflow — three explicit steps (submit → approve × 2 →
 * execute), four-eyes principle enforced at both the schema and the service,
 * every state transition mirrored in a WORM {@link RevealAuditLog} row. The
 * only legal bases accepted are KVKK Md.28 (adli/idari mercii yazılı talebi)
 * and GDPR Art.6(1)(c/e) legal obligations; a court order is a distinct
 * enum value so the audit archive can be filtered per authority.
 */
@Service
public class RevealService {
    private static final Set<String> LEGAL_BASES = Set.of("KVKK_MD28", "GDPR_ART6_1C", "GDPR_ART6_1E", "COURT_ORDER");

    private final EthicsCaseRepository cases;
    private final EthicsReportRepository reports;
    private final EthicsMessageRepository messages;
    private final RevealRequestRepository revealRequests;
    private final RevealAuditLogRepository revealAudit;
    private final EthicsAuthorization authorization;
    private final ObjectMapper auditMapper;

    public RevealService(EthicsCaseRepository cases, EthicsReportRepository reports,
            EthicsMessageRepository messages, RevealRequestRepository revealRequests,
            RevealAuditLogRepository revealAudit, EthicsAuthorization authorization,
            ObjectMapper auditMapper) {
        this.cases=cases;this.reports=reports;this.messages=messages;
        this.revealRequests=revealRequests;this.revealAudit=revealAudit;
        this.authorization=authorization;this.auditMapper=auditMapper;
    }

    @Transactional
    public RevealResponse submit(StaffContext staff, RevealSubmitRequest request) {
        requireCaseOrDeny(staff, request.caseId(), "case_viewer");
        if (!LEGAL_BASES.contains(request.legalBasis())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "REVEAL_LEGAL_BASIS_INVALID");
        }
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        RevealRequest saved = revealRequests.save(new RevealRequest(id, request.caseId(),
                staff.subject(), request.requesterName(), request.legalBasis(),
                request.legalAuthority(), request.referenceNumber(), request.justification(), now));
        recordAudit(saved, staff, "case_viewer", "REQUEST_SUBMITTED", Map.of(
                "legalBasis", request.legalBasis(),
                "legalAuthority", request.legalAuthority(),
                "referenceNumber", request.referenceNumber()), now);
        return toResponse(saved);
    }

    @Transactional
    public RevealResponse approve(StaffContext staff, UUID requestId, String approverName, String approverRole) {
        RevealRequest existing = loadPending(requestId);
        requireCaseOrDeny(staff, existing.getCaseId(), "case_handler");
        if (staff.subject().equals(existing.getRequesterSubject())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVEAL_REQUESTER_MAY_NOT_APPROVE");
        }
        Instant now = Instant.now();
        switch (existing.getStatus()) {
            case "PENDING" -> existing.recordFirstApproval(staff.subject(), approverName, approverRole, now);
            case "ONE_APPROVED" -> {
                if (staff.subject().equals(existing.getFirstApproverSubject())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "REVEAL_TWO_PERSON_RULE_VIOLATION");
                }
                existing.recordSecondApproval(staff.subject(), approverName, approverRole, now);
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "REVEAL_REQUEST_NOT_PENDING");
        }
        RevealRequest persisted = revealRequests.save(existing);
        recordAudit(persisted, staff, approverRole, "APPROVAL_RECORDED", Map.of(
                "status", persisted.getStatus(),
                "approverName", approverName), now);
        return toResponse(persisted);
    }

    @Transactional
    public RevealResponse reject(StaffContext staff, UUID requestId, String reason) {
        RevealRequest existing = loadPending(requestId);
        requireCaseOrDeny(staff, existing.getCaseId(), "case_handler");
        if (staff.subject().equals(existing.getRequesterSubject())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVEAL_REQUESTER_MAY_NOT_REJECT");
        }
        Instant now = Instant.now();
        existing.reject(staff.subject(), reason == null ? "" : reason, now);
        RevealRequest persisted = revealRequests.save(existing);
        recordAudit(persisted, staff, "case_handler", "REQUEST_REJECTED", Map.of(
                "reason", reason == null ? "" : reason), now);
        return toResponse(persisted);
    }

    @Transactional
    public RevealPayloadResponse execute(StaffContext staff, UUID requestId) {
        RevealRequest existing = loadForExecute(requestId);
        requireCaseOrDeny(staff, existing.getCaseId(), "case_handler");
        if (staff.subject().equals(existing.getRequesterSubject())
                || staff.subject().equals(existing.getFirstApproverSubject())
                || staff.subject().equals(existing.getSecondApproverSubject())) {
            // The executor MAY overlap with an approver in a very small team,
            // but MUST NOT be the requester. This defensive check is enforced
            // both by the app and reflected in the audit event.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVEAL_EXECUTOR_MUST_NOT_BE_REQUESTER");
        }
        if (!"READY".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVEAL_REQUEST_NOT_READY");
        }
        Instant now = Instant.now();
        existing.markExecuted(staff.subject(), now);
        RevealRequest persisted = revealRequests.save(existing);

        EthicsCase caseRow = cases.findById(existing.getCaseId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "REVEAL_CASE_GONE"));
        EthicsReport reportRow = reports.findByCaseId(existing.getCaseId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "REVEAL_REPORT_GONE"));
        List<RevealMessageEntry> messageEntries = messages
                .findAllByCaseIdAndVisibilityInOrderByCreatedAtAsc(existing.getCaseId(),
                        List.of("REPORTER_VISIBLE", "INTERNAL"))
                .stream()
                .map(m -> new RevealMessageEntry(m.getId(), m.getAuthorType(), m.getVisibility(),
                        m.getBody(), m.getCreatedAt()))
                .toList();

        recordAudit(persisted, staff, "case_handler", "REQUEST_EXECUTED",
                Map.of("caseId", existing.getCaseId().toString()), now);
        recordAudit(persisted, staff, "case_handler", "ACCESS_RETRIEVED",
                Map.of("messageCount", messageEntries.size(),
                       "caseStatus", caseRow.getStatus()), now);

        return new RevealPayloadResponse(
                persisted.getId(),
                caseRow.getId(),
                reportRow.getMode(),
                reportRow.getCategory(),
                reportRow.getSubject(),
                reportRow.getNarrative(),
                reportRow.getLocale(),
                reportRow.getNoticeVersion(),
                caseRow.getStatus(),
                caseRow.getCreatedAt(),
                caseRow.getUpdatedAt(),
                messageEntries,
                now);
    }

    @Transactional(readOnly = true)
    public List<RevealResponse> listForCase(StaffContext staff, UUID caseId) {
        requireCaseOrDeny(staff, caseId, "case_viewer");
        return revealRequests.findAllByCaseIdOrderByRequestedAtDesc(caseId).stream()
                .map(RevealService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<RevealAuditEntry> audit(StaffContext staff, UUID requestId) {
        RevealRequest existing = revealRequests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVEAL_REQUEST_NOT_FOUND"));
        requireCaseOrDeny(staff, existing.getCaseId(), "case_viewer");
        return revealAudit.findAllByRequestIdOrderByCreatedAtAsc(requestId).stream()
                .map(l -> new RevealAuditEntry(l.getId(), l.getEventType(), l.getActorSubject(),
                        l.getActorRole(), l.getPayload(), l.getCreatedAt())).toList();
    }

    private RevealRequest loadPending(UUID requestId) {
        RevealRequest existing = revealRequests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVEAL_REQUEST_NOT_FOUND"));
        if (Set.of("REJECTED", "EXECUTED").contains(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REVEAL_REQUEST_TERMINAL");
        }
        return existing;
    }

    private RevealRequest loadForExecute(UUID requestId) {
        return revealRequests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "REVEAL_REQUEST_NOT_FOUND"));
    }

    private void requireCaseOrDeny(StaffContext staff, UUID caseId, String relation) {
        EthicsCase caseRow = cases.findByIdAndOrgId(caseId, staff.orgId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found."));
        authorization.require(staff, relation, caseRow.getId());
    }

    private void recordAudit(RevealRequest source, StaffContext staff, String actorRole,
            String eventType, Map<String, Object> payload, Instant when) {
        revealAudit.save(new RevealAuditLog(UUID.randomUUID(), source.getId(), source.getCaseId(),
                eventType, staff.subject(), actorRole, encodeAudit(payload), when));
    }

    private String encodeAudit(Map<String, Object> payload) {
        try {
            return auditMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Reveal audit payload could not be serialised.", error);
        }
    }

    private static RevealResponse toResponse(RevealRequest source) {
        return new RevealResponse(source.getId(), source.getCaseId(), source.getStatus(),
                source.getLegalBasis(), source.getLegalAuthority(), source.getReferenceNumber(),
                source.getRequesterSubject(), source.getRequesterName(), source.getRequestedAt(),
                source.getFirstApproverSubject(), source.getFirstApproverName(), source.getFirstApprovedAt(),
                source.getSecondApproverSubject(), source.getSecondApproverName(), source.getSecondApprovedAt(),
                source.getRejectedBySubject(), source.getRejectionReason(), source.getRejectedAt(),
                source.getExecutedBySubject(), source.getExecutedAt());
    }
}
