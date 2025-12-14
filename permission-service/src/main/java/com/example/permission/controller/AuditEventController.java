package com.example.permission.controller;

import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.service.AuditEventService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/audit/events")
public class AuditEventController {

    private final AuditEventService auditEventService;

    public AuditEventController(AuditEventService auditEventService) {
        this.auditEventService = auditEventService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit-read')")
    public ResponseEntity<AuditEventPageResponse> listEvents(@RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
                                                             @RequestParam(required = false) String sort,
                                                             @RequestParam Map<String, String> query) {
        // Support legacy 'size' param as alias for pageSize
        int effectiveSize = pageSize;
        if (query.containsKey("size")) {
            try { effectiveSize = Integer.parseInt(query.get("size")); } catch (Exception ignored) {}
        }

        // id shortcut: deterministic fetch or 404
        String id = query.get("id");
        if (id != null && !id.isBlank()) {
            AuditEventPageResponse single = auditEventService.findByIdPage(id);
            return ResponseEntity.ok(single);
        }

        Map<String, String> filters = extractFilters(query);
        // Convert 1-based 'page' to 0-based for Spring Data
        int zeroBasedPage = Math.max(1, page) - 1;
        AuditEventPageResponse result = auditEventService.listEvents(zeroBasedPage, effectiveSize, sort, filters);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('audit-export')")
    public ResponseEntity<byte[]> exportEvents(@RequestParam(defaultValue = "json") String format,
                                               @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) String sort,
                                               @RequestParam Map<String, String> query) {
        Map<String, String> filters = extractFilters(query);
        var events = auditEventService.exportEvents(sort, filters, limit);
        byte[] payload = auditEventService.buildExportPayload(events, format);

        String sanitizedFormat = "csv".equalsIgnoreCase(format) ? "csv" : "json";
        String filename = "audit-events." + sanitizedFormat;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=%s".formatted(filename))
                .contentType("csv".equalsIgnoreCase(sanitizedFormat)
                        ? MediaType.parseMediaType("text/csv")
                        : MediaType.APPLICATION_JSON)
                .body(payload);
    }

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter liveEvents() {
        return auditEventService.openLiveStream();
    }

    private Map<String, String> extractFilters(Map<String, String> query) {
        Map<String, String> filters = new HashMap<>();
        query.forEach((key, value) -> {
            if (key.startsWith("filter[")) {
                String filterKey = key.substring(7, key.length() - 1);
                filters.put(filterKey, value);
            }
            if ("dateFrom".equals(key) || "dateTo".equals(key)) {
                filters.put(key, value);
            }
            if ("action".equals(key) || "correlationId".equals(key)) {
                filters.put(key, value);
            }
            // Spec-aligned aliases
            if ("from".equals(key)) {
                filters.put("dateFrom", value);
            }
            if ("to".equals(key)) {
                filters.put("dateTo", value);
            }
            if ("user".equals(key)) {
                filters.put("userEmail", value);
            }
            if ("service".equals(key) || "level".equals(key) || "search".equals(key)) {
                filters.put(key, value);
            }
        });
        return filters;
    }
}
