package com.example.meeting.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.meeting.dto.v1.admin.MeetingActionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingActionResponse;
import com.example.meeting.dto.v1.admin.MeetingActionUpdateRequest;
import com.example.meeting.dto.v1.admin.MeetingDecisionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingDecisionResponse;
import com.example.meeting.dto.v1.admin.MeetingDecisionUpdateRequest;
import com.example.meeting.dto.v1.admin.MeetingSessionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingSessionResponse;
import com.example.meeting.dto.v1.admin.MeetingSessionUpdateRequest;
import com.example.meeting.dto.v1.admin.RecordingLifecycleResponse;
import com.example.meeting.dto.v1.admin.RecordingLifecycleSyncRequest;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Sub-resource admin REST surface for a meeting — Faz 24 (#410). Sessions,
 * actions, and decisions all hang off {@code /meetings/{meetingId}/...}
 * and reuse the {@code module:meeting} can_view / can_manage gate.
 *
 * <pre>
 * .../{meetingId}/sessions      GET(list) POST(create)
 * .../{meetingId}/sessions/{id} GET PUT DELETE
 * .../{meetingId}/actions       GET(list) POST(create)
 * .../{meetingId}/actions/{id}  GET PUT DELETE
 * .../{meetingId}/decisions     GET(list) POST(create)
 * .../{meetingId}/decisions/{id} GET PUT DELETE
 * </pre>
 *
 * <p>A sub-resource is reachable only through a meeting the caller can see
 * (the service resolves the parent first; a foreign / unknown meeting →
 * 404 with no existence leak).
 */
@RestController
@RequestMapping("/api/v1/admin/meetings/{meetingId}")
public class MeetingSubResourceController {

    private final MeetingService meetingService;
    private final TenantContextResolver tenantContextResolver;

    public MeetingSubResourceController(
            MeetingService meetingService,
            TenantContextResolver tenantContextResolver) {
        this.meetingService = meetingService;
        this.tenantContextResolver = tenantContextResolver;
    }

    // ───────────────────────────── Sessions ─────────────────────────────

    @GetMapping("/sessions")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public List<MeetingSessionResponse> listSessions(@PathVariable UUID meetingId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.listSessions(tenant, meetingId);
    }

    @GetMapping("/sessions/{sessionId}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public MeetingSessionResponse getSession(
            @PathVariable UUID meetingId, @PathVariable UUID sessionId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.getSession(tenant, meetingId, sessionId);
    }

    @PostMapping("/sessions")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<MeetingSessionResponse> createSession(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingSessionCreateRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        MeetingSessionResponse created = meetingService.createSession(tenant, meetingId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/recording-lifecycle")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public RecordingLifecycleResponse syncRecordingLifecycle(
            @PathVariable UUID meetingId,
            @Valid @RequestBody RecordingLifecycleSyncRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.syncRecordingLifecycle(tenant, meetingId, request);
    }

    @PutMapping("/sessions/{sessionId}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public MeetingSessionResponse updateSession(
            @PathVariable UUID meetingId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody MeetingSessionUpdateRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.updateSession(tenant, meetingId, sessionId, request);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<Void> deleteSession(
            @PathVariable UUID meetingId, @PathVariable UUID sessionId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        meetingService.deleteSession(tenant, meetingId, sessionId);
        return ResponseEntity.accepted().build();
    }

    // ───────────────────────────── Actions ─────────────────────────────

    @GetMapping("/actions")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public List<MeetingActionResponse> listActions(@PathVariable UUID meetingId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.listActions(tenant, meetingId);
    }

    @GetMapping("/actions/{actionId}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public MeetingActionResponse getAction(
            @PathVariable UUID meetingId, @PathVariable UUID actionId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.getAction(tenant, meetingId, actionId);
    }

    @PostMapping("/actions")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<MeetingActionResponse> createAction(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingActionCreateRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        MeetingActionResponse created = meetingService.createAction(tenant, meetingId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/actions/{actionId}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public MeetingActionResponse updateAction(
            @PathVariable UUID meetingId,
            @PathVariable UUID actionId,
            @Valid @RequestBody MeetingActionUpdateRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.updateAction(tenant, meetingId, actionId, request);
    }

    @DeleteMapping("/actions/{actionId}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<Void> deleteAction(
            @PathVariable UUID meetingId, @PathVariable UUID actionId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        meetingService.deleteAction(tenant, meetingId, actionId);
        return ResponseEntity.noContent().build();
    }

    // ───────────────────────────── Decisions ─────────────────────────────

    @GetMapping("/decisions")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public List<MeetingDecisionResponse> listDecisions(@PathVariable UUID meetingId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.listDecisions(tenant, meetingId);
    }

    @GetMapping("/decisions/{decisionId}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public MeetingDecisionResponse getDecision(
            @PathVariable UUID meetingId, @PathVariable UUID decisionId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.getDecision(tenant, meetingId, decisionId);
    }

    @PostMapping("/decisions")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<MeetingDecisionResponse> createDecision(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingDecisionCreateRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        MeetingDecisionResponse created = meetingService.createDecision(tenant, meetingId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/decisions/{decisionId}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public MeetingDecisionResponse updateDecision(
            @PathVariable UUID meetingId,
            @PathVariable UUID decisionId,
            @Valid @RequestBody MeetingDecisionUpdateRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.updateDecision(tenant, meetingId, decisionId, request);
    }

    @DeleteMapping("/decisions/{decisionId}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<Void> deleteDecision(
            @PathVariable UUID meetingId, @PathVariable UUID decisionId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        meetingService.deleteDecision(tenant, meetingId, decisionId);
        return ResponseEntity.noContent().build();
    }
}
