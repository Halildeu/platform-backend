package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestApproval;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestCreate;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestRejection;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestResponse;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogUninstallSettingsChangeRequest;
import com.example.endpointadmin.model.CatalogUninstallSettingsChangeRequestState;
import com.example.endpointadmin.model.CatalogUninstallSettingsField;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.repository.CatalogUninstallSettingsChangeRequestRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AG-028 Phase 0 — catalog uninstall settings change-request service
 * (Faz 22.5.6).
 *
 * <p>Routes flag flips on APPROVED catalog rows through a propose/approve
 * maker-checker cycle. Direct PATCH on the catalog row is REJECTED for
 * uninstall flag fields via {@link EndpointSoftwareCatalogService}
 * regression guard (DTO does not carry the flag fields; only this
 * service path writes them to the catalog row).
 *
 * <p>Cross-AI plan-time Codex consensus thread
 * {@code 019e8c8a-4c90-7c00-8f64-c88d47801a06} iter-6 AGREE.
 *
 * <p>Lifecycle: {@code propose} → state {@code PROPOSED}; {@code approve}
 * transitions {@code PROPOSED → APPLIED} in the same transaction and
 * applies the flag flip on the catalog row; {@code reject} transitions
 * {@code PROPOSED → REJECTED}.
 *
 * <p>RBAC enforced at the controller layer via existing
 * {@code module:endpoint-admin/can_manage} relation. Elevated approver
 * role for {@code uninstall_protected = true → false} transitions is a
 * service-layer guard ({@link #requireElevatedIfUnprotect}).
 */
@Service
public class CatalogUninstallSettingsChangeRequestService {

    static final String EVENT_PROPOSED = "ENDPOINT_CATALOG_UNINSTALL_SETTINGS_PROPOSED";
    static final String EVENT_APPROVED_APPLIED = "ENDPOINT_CATALOG_UNINSTALL_SETTINGS_APPROVED_APPLIED";
    static final String EVENT_REJECTED = "ENDPOINT_CATALOG_UNINSTALL_SETTINGS_REJECTED";
    static final String EVENT_MAKER_CHECKER_REJECTED =
            "ENDPOINT_CATALOG_UNINSTALL_SETTINGS_APPROVAL_REJECTED_MAKER_CHECKER";
    static final String EVENT_ELEVATED_REQUIRED =
            "ENDPOINT_CATALOG_UNINSTALL_SETTINGS_APPROVAL_REJECTED_ELEVATED_REQUIRED";

    private static final String ACTION_PROPOSE = "PROPOSE_CATALOG_UNINSTALL_SETTINGS_CHANGE";
    private static final String ACTION_APPROVE = "APPROVE_CATALOG_UNINSTALL_SETTINGS_CHANGE";
    private static final String ACTION_REJECT = "REJECT_CATALOG_UNINSTALL_SETTINGS_CHANGE";

    private final CatalogUninstallSettingsChangeRequestRepository repository;
    private final EndpointSoftwareCatalogItemRepository catalogRepository;
    private final EndpointAuditService auditService;

    public CatalogUninstallSettingsChangeRequestService(
            CatalogUninstallSettingsChangeRequestRepository repository,
            EndpointSoftwareCatalogItemRepository catalogRepository,
            EndpointAuditService auditService) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AdminCatalogUninstallSettingsChangeRequestResponse propose(
            AdminTenantContext context,
            UUID catalogItemId,
            AdminCatalogUninstallSettingsChangeRequestCreate request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Change-request body is required.");
        }
        UUID tenantId = context.tenantId();
        String subject = context.subject();

        EndpointSoftwareCatalogItem catalog = catalogRepository
                .findByTenantIdAndId(tenantId, catalogItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Catalog item not found in tenant scope."));
        if (catalog.getStatus() != CatalogItemStatus.APPROVED) {
            // Flag flips are only meaningful on APPROVED rows; DRAFT/REVOKED
            // catalog rows go through the existing DRAFT→APPROVED flow.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Catalog item is not APPROVED; flag change-request applies only to APPROVED rows.");
        }
        boolean currentValue = switch (request.field()) {
            case UNINSTALL_SUPPORTED -> catalog.isUninstallSupported();
            case UNINSTALL_PROTECTED -> catalog.isUninstallProtected();
        };
        if (currentValue == request.newValue()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Catalog item already has the requested flag value; nothing to change.");
        }

        // Read-side open-request guard before relying on the DB partial unique index.
        if (repository.findOpenForCatalogItemAndField(tenantId, catalogItemId, request.field())
                .isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An open change-request already exists for this catalog item + field.");
        }

        CatalogUninstallSettingsChangeRequest entity = new CatalogUninstallSettingsChangeRequest();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setCatalogItemId(catalogItemId);
        entity.setField(request.field());
        entity.setNewValue(request.newValue());
        entity.setProposedBy(subject);
        entity.setState(CatalogUninstallSettingsChangeRequestState.PROPOSED);
        entity.setReason(request.reason());

        CatalogUninstallSettingsChangeRequest saved;
        try {
            saved = repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException dive) {
            // Race lost to a parallel propose — DB partial unique index fired.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An open change-request already exists for this catalog item + field.");
        }

        auditService.record(
                tenantId,
                null,
                null,
                EVENT_PROPOSED,
                ACTION_PROPOSE,
                subject,
                saved.getId().toString(),
                buildAuditMetadata(saved, catalog, currentValue),
                null,
                snapshotAfter(saved));

        return AdminCatalogUninstallSettingsChangeRequestResponse.from(saved);
    }

    @Transactional
    public AdminCatalogUninstallSettingsChangeRequestResponse approve(
            AdminTenantContext context,
            UUID catalogItemId,
            UUID requestId,
            AdminCatalogUninstallSettingsChangeRequestApproval body) {
        UUID tenantId = context.tenantId();
        String subject = context.subject();

        CatalogUninstallSettingsChangeRequest req = repository
                .findByTenantIdAndId(tenantId, requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Change-request not found in tenant scope."));
        if (!req.getCatalogItemId().equals(catalogItemId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "catalogItemId in path does not match the request's catalog item.");
        }
        if (req.getState() != CatalogUninstallSettingsChangeRequestState.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Change-request is not in PROPOSED state; current state="
                            + req.getState());
        }
        if (req.getProposedBy().equals(subject)) {
            // Maker-checker violation — DB CHECK also rejects, but we emit
            // a durable audit event so the rejection is recorded even when
            // the transaction rolls back.
            auditService.record(
                    tenantId,
                    null,
                    null,
                    EVENT_MAKER_CHECKER_REJECTED,
                    ACTION_APPROVE,
                    subject,
                    req.getId().toString(),
                    Map.of("requestId", req.getId().toString(),
                            "field", req.getField().name(),
                            "proposedBy", req.getProposedBy()),
                    null,
                    null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Maker-checker violation: approver must differ from proposer.");
        }

        EndpointSoftwareCatalogItem catalog = catalogRepository
                .findByTenantIdAndId(tenantId, catalogItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Catalog item not found in tenant scope."));
        // Elevated-approver service guard for unprotect transitions
        // (uninstall_protected: true → false). Codex iter-2 P0 #4 absorb.
        requireElevatedIfUnprotect(req, catalog, subject);

        Instant now = Instant.now();
        req.setApprovedBy(subject);
        req.setApprovedAt(now);
        req.setAppliedAt(now);
        req.setState(CatalogUninstallSettingsChangeRequestState.APPLIED);

        // Apply the flag flip on the catalog row in the SAME transaction.
        applyApproved(req, catalog, subject);

        CatalogUninstallSettingsChangeRequest savedReq = repository.save(req);

        auditService.record(
                tenantId,
                null,
                null,
                EVENT_APPROVED_APPLIED,
                ACTION_APPROVE,
                subject,
                savedReq.getId().toString(),
                buildAuditMetadata(savedReq, catalog, !req.isNewValue()),
                null,
                snapshotAfter(savedReq));

        return AdminCatalogUninstallSettingsChangeRequestResponse.from(savedReq);
    }

    @Transactional
    public AdminCatalogUninstallSettingsChangeRequestResponse reject(
            AdminTenantContext context,
            UUID catalogItemId,
            UUID requestId,
            AdminCatalogUninstallSettingsChangeRequestRejection body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Rejection body is required.");
        }
        UUID tenantId = context.tenantId();
        String subject = context.subject();

        CatalogUninstallSettingsChangeRequest req = repository
                .findByTenantIdAndId(tenantId, requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Change-request not found in tenant scope."));
        if (!req.getCatalogItemId().equals(catalogItemId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "catalogItemId in path does not match the request's catalog item.");
        }
        if (req.getState() != CatalogUninstallSettingsChangeRequestState.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Change-request is not in PROPOSED state; current state="
                            + req.getState());
        }

        req.setState(CatalogUninstallSettingsChangeRequestState.REJECTED);
        req.setRejectReason(body.rejectReason());
        // approval pair intentionally left NULL on REJECTED.

        CatalogUninstallSettingsChangeRequest savedReq = repository.save(req);

        auditService.record(
                tenantId,
                null,
                null,
                EVENT_REJECTED,
                ACTION_REJECT,
                subject,
                savedReq.getId().toString(),
                Map.of("requestId", savedReq.getId().toString(),
                        "field", savedReq.getField().name(),
                        "rejectReason", body.rejectReason()),
                null,
                snapshotAfter(savedReq));

        return AdminCatalogUninstallSettingsChangeRequestResponse.from(savedReq);
    }

    @Transactional(readOnly = true)
    public AdminCatalogUninstallSettingsChangeRequestResponse get(
            AdminTenantContext context, UUID catalogItemId, UUID requestId) {
        UUID tenantId = context.tenantId();
        CatalogUninstallSettingsChangeRequest req = repository
                .findByTenantIdAndId(tenantId, requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Change-request not found in tenant scope."));
        if (!req.getCatalogItemId().equals(catalogItemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Change-request not found for this catalog item.");
        }
        return AdminCatalogUninstallSettingsChangeRequestResponse.from(req);
    }

    @Transactional(readOnly = true)
    public List<AdminCatalogUninstallSettingsChangeRequestResponse> listForCatalogItem(
            AdminTenantContext context, UUID catalogItemId) {
        UUID tenantId = context.tenantId();
        List<CatalogUninstallSettingsChangeRequest> rows = repository
                .findByTenantIdAndCatalogItemIdOrderByProposedAtDesc(tenantId, catalogItemId);
        return rows.stream()
                .map(AdminCatalogUninstallSettingsChangeRequestResponse::from)
                .toList();
    }

    // ───────────────────────────── helpers

    private void applyApproved(CatalogUninstallSettingsChangeRequest req,
                               EndpointSoftwareCatalogItem catalog,
                               String subject) {
        switch (req.getField()) {
            case UNINSTALL_SUPPORTED -> catalog.setUninstallSupported(req.isNewValue());
            case UNINSTALL_PROTECTED -> catalog.setUninstallProtected(req.isNewValue());
        }
        catalog.setLastUpdatedBySubject(subject);
        catalogRepository.save(catalog);
    }

    /**
     * Elevated approver service-layer guard. Applied for
     * {@code uninstall_protected: true → false} transitions only — i.e.
     * unprotecting a previously-protected catalog row. Until the platform
     * grows an elevated/super-admin role surface this guard rejects with
     * 403 to keep the unprotect path closed.
     *
     * <p>Codex iter-2 P0 #4 absorb: protected denylist hard guard; unprotect
     * must not be a routine PATCH.
     */
    private void requireElevatedIfUnprotect(CatalogUninstallSettingsChangeRequest req,
                                            EndpointSoftwareCatalogItem catalog,
                                            String subject) {
        if (req.getField() == CatalogUninstallSettingsField.UNINSTALL_PROTECTED
                && catalog.isUninstallProtected() && !req.isNewValue()) {
            UUID tenantId = req.getTenantId();
            auditService.record(
                    tenantId,
                    null,
                    null,
                    EVENT_ELEVATED_REQUIRED,
                    ACTION_APPROVE,
                    subject,
                    req.getId().toString(),
                    Map.of("requestId", req.getId().toString(),
                            "field", req.getField().name(),
                            "transition", "PROTECTED_TRUE_TO_FALSE"),
                    null,
                    null);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Unprotecting an uninstall-protected catalog row requires elevated approver role.");
        }
    }

    private static Map<String, Object> buildAuditMetadata(
            CatalogUninstallSettingsChangeRequest req,
            EndpointSoftwareCatalogItem catalog,
            boolean previousValue) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", req.getId().toString());
        meta.put("catalogItemId", req.getCatalogItemId().toString());
        meta.put("catalogPackageId", catalog.getPackageId());
        meta.put("field", req.getField().name());
        meta.put("newValue", req.isNewValue());
        meta.put("previousValue", previousValue);
        meta.put("state", req.getState().name());
        return meta;
    }

    private static Map<String, Object> snapshotAfter(CatalogUninstallSettingsChangeRequest req) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("requestId", req.getId().toString());
        snap.put("catalogItemId", req.getCatalogItemId().toString());
        snap.put("field", req.getField().name());
        snap.put("newValue", req.isNewValue());
        snap.put("state", req.getState().name());
        snap.put("proposedBy", req.getProposedBy());
        snap.put("approvedBy", req.getApprovedBy());
        return snap;
    }
}
