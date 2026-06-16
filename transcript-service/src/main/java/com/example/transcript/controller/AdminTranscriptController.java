package com.example.transcript.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.transcript.dto.CreateTranscriptSegmentRequest;
import com.example.transcript.dto.TranscriptSegmentDto;
import com.example.transcript.dto.TranscriptSegmentPageDto;
import com.example.transcript.dto.UpdateTranscriptSegmentRequest;
import com.example.transcript.security.AdminTenantContext;
import com.example.transcript.security.TranscriptAuthz;
import com.example.transcript.security.TenantContextResolver;
import com.example.transcript.service.TranscriptExportService;
import com.example.transcript.service.TranscriptSegmentService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Admin transcript-segment API.
 *
 * <p>Class-level {@code @RequireModule(transcript, can_view)} gates every route
 * at the read tier; mutating routes add {@code can_manage} at the method level
 * (the interceptor honors the most-specific {@code @RequireModule}). Tenant
 * scope + authz principal are always resolved from the authenticated context.
 *
 * <p>KVKK m.12: the read/list/search/export routes record an access-audit row
 * inside the service layer — there is no path to read transcript personal data
 * here without an audit row being written.
 */
@RestController
@RequestMapping("/api/v1/admin/transcripts")
@RequireModule(value = TranscriptAuthz.MODULE, relation = TranscriptAuthz.VIEWER)
public class AdminTranscriptController {

    private final TranscriptSegmentService segmentService;
    private final TranscriptExportService exportService;
    private final TenantContextResolver tenantContextResolver;

    public AdminTranscriptController(TranscriptSegmentService segmentService,
                                     TranscriptExportService exportService,
                                     TenantContextResolver tenantContextResolver) {
        this.segmentService = segmentService;
        this.exportService = exportService;
        this.tenantContextResolver = tenantContextResolver;
    }

    // ───────────────────────────── CREATE ─────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireModule(value = TranscriptAuthz.MODULE, relation = TranscriptAuthz.MANAGER)
    public TranscriptSegmentDto create(@Valid @RequestBody CreateTranscriptSegmentRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return segmentService.create(context, request);
    }

    // ────────────────────────────── READ ──────────────────────────────

    @GetMapping("/{segmentId}")
    public TranscriptSegmentDto getSegment(@PathVariable UUID segmentId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return segmentService.getSegment(context, segmentId);
    }

    // ────────────────────────────── LIST ──────────────────────────────

    /**
     * List segments by meeting OR session (exactly one required). Paged.
     * Records a KVKK m.12 LIST access-audit row.
     */
    @GetMapping
    public TranscriptSegmentPageDto list(
            @RequestParam(required = false) UUID meetingId,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "0") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        if ((meetingId == null) == (sessionId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Exactly one of meetingId or sessionId is required.");
        }
        Page<TranscriptSegmentDto> result = meetingId != null
                ? segmentService.listByMeeting(context, meetingId, page, size)
                : segmentService.listBySession(context, sessionId, page, size);
        return TranscriptSegmentPageDto.from(result);
    }

    // ───────────────────────────── SEARCH ─────────────────────────────

    /**
     * Search segment text (draft + final), case-insensitive substring,
     * optionally scoped to a meeting. Records a KVKK m.12 SEARCH access-audit
     * row (the query term is NOT persisted).
     */
    @GetMapping("/search")
    public TranscriptSegmentPageDto search(
            @RequestParam String query,
            @RequestParam(required = false) UUID meetingId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "0") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        Page<TranscriptSegmentDto> result = segmentService.search(context, query, meetingId, page, size);
        return TranscriptSegmentPageDto.from(result);
    }

    // ───────────────────────────── EXPORT ─────────────────────────────

    /**
     * Export a meeting's segments. {@code format=json} (default) returns the DTO
     * list; {@code format=csv} streams a UTF-8 CSV via the shared exporter. Both
     * record a KVKK m.12 EXPORT access-audit row before producing the body.
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(
            @RequestParam UUID meetingId,
            @RequestParam(defaultValue = "json") String format) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        String fmt = format.trim().toLowerCase(java.util.Locale.ROOT);
        if ("csv".equals(fmt)) {
            TranscriptExportService.CsvPlan plan = exportService.prepareCsv(context, meetingId);
            StreamingResponseBody body = out -> writeCsv(plan, out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + plan.filename() + "\"")
                    .contentType(MediaType.parseMediaType(plan.contentType()))
                    .body(body);
        }
        if ("json".equals(fmt)) {
            List<TranscriptSegmentDto> segments = segmentService.exportByMeeting(context, meetingId);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(segments);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be 'json' or 'csv'.");
    }

    // ───────────────────────────── UPDATE ─────────────────────────────

    @PatchMapping("/{segmentId}")
    @RequireModule(value = TranscriptAuthz.MODULE, relation = TranscriptAuthz.MANAGER)
    public TranscriptSegmentDto update(
            @PathVariable UUID segmentId,
            @Valid @RequestBody UpdateTranscriptSegmentRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return segmentService.update(context, segmentId, request);
    }

    // ───────────────────────────── DELETE ─────────────────────────────

    @DeleteMapping("/{segmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequireModule(value = TranscriptAuthz.MODULE, relation = TranscriptAuthz.MANAGER)
    public void delete(@PathVariable UUID segmentId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        segmentService.delete(context, segmentId);
    }

    private void writeCsv(TranscriptExportService.CsvPlan plan, OutputStream out) throws IOException {
        exportService.streamCsv(plan, out);
    }
}
