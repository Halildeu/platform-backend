package com.example.permission.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.permission.dto.v1.AuditEventIngestRequestDto;
import com.example.permission.dto.v1.PermissionMutationAckDto;
import com.example.permission.service.AuditEventService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/internal/audit/events")
public class InternalAuditEventController {

    private final AuditEventService auditEventService;

    public InternalAuditEventController(AuditEventService auditEventService) {
        this.auditEventService = auditEventService;
    }

    @PostMapping
    @PreAuthorize("hasRole('INTERNAL')")
    public ResponseEntity<PermissionMutationAckDto> ingestEvent(@Valid @RequestBody AuditEventIngestRequestDto request) {
        var saved = auditEventService.recordMirroredEvent(request);
        String auditId = saved != null && saved.getId() != null ? saved.getId().toString() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PermissionMutationAckDto("accepted", auditId));
    }
}
