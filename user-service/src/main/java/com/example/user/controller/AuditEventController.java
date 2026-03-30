package com.example.user.controller;

import com.example.user.model.UserAuditEvent;
import com.example.user.repository.UserAuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit event endpoints — migrated from permission-service.
 * Serves /api/audit/events for the audit UI.
 */
@RestController
@RequestMapping("/api/audit/events")
public class AuditEventController {

    private final UserAuditEventRepository repository;

    public AuditEventController(UserAuditEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 500);
        Page<UserAuditEvent> result = repository.findAll(
                PageRequest.of(safePage, safeSize, Sort.by("occurredAt").descending()));

        List<Map<String, Object>> events = result.getContent().stream()
                .map(this::mapToResponse)
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("events", events);
        response.put("page", safePage);
        response.put("total", result.getTotalElements());
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> mapToResponse(UserAuditEvent event) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", event.getId());
        map.put("timestamp", event.getOccurredAt());
        map.put("userEmail", event.getTargetUserId() != null ? "user:" + event.getTargetUserId() : null);
        map.put("service", "user-service");
        map.put("level", event.getEventType() != null && event.getEventType().contains("FAIL") ? "ERROR" : "INFO");
        map.put("action", event.getEventType());
        map.put("details", event.getDetails());
        map.put("correlationId", "audit-" + event.getId());
        return map;
    }
}
