package com.example.meeting.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.meeting.dto.v1.admin.MeetingCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingResponse;
import com.example.meeting.dto.v1.admin.MeetingSearchCriteria;
import com.example.meeting.dto.v1.admin.MeetingUpdateRequest;
import com.example.meeting.dto.v1.admin.PagedResponse;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import com.example.meeting.service.MeetingHistorySearchMetrics;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Meeting CRUD admin REST surface — Faz 24 (#410).
 *
 * <pre>
 * GET    /api/v1/admin/meetings?page=&amp;size=&amp;status=&amp;title=&amp;meetingId=&amp;dateFrom=&amp;dateTo=
 *                                                          — search/list (can_view)
 * GET    /api/v1/admin/meetings/{id}                  — single (can_view)
 * POST   /api/v1/admin/meetings                       — create (can_manage)
 * PUT    /api/v1/admin/meetings/{id}                  — update (can_manage)
 * DELETE /api/v1/admin/meetings/{id}                  — delete (can_manage)
 * </pre>
 *
 * <p>The {@code @RequireModule} OpenFGA check (module:meeting can_view /
 * can_manage) is the fine-grained gate, enforced by
 * {@code MeetingRequireModuleInterceptor}. Tenant/org scoping happens in
 * {@link MeetingService} from the resolved {@link AdminTenantContext}.
 */
@RestController
@RequestMapping("/api/v1/admin/meetings")
public class MeetingAdminController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final MeetingService meetingService;
    private final TenantContextResolver tenantContextResolver;
    private final MeetingHistorySearchMetrics searchMetrics;

    public MeetingAdminController(
            MeetingService meetingService,
            TenantContextResolver tenantContextResolver,
            MeetingHistorySearchMetrics searchMetrics) {
        this.meetingService = meetingService;
        this.tenantContextResolver = tenantContextResolver;
        this.searchMetrics = searchMetrics;
    }

    @GetMapping
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public PagedResponse<MeetingResponse> list(
            @RequestParam(required = false) MeetingStatus status,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) UUID meetingId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        MeetingSearchCriteria criteria;
        Pageable pageable;
        try {
            criteria = MeetingSearchCriteria.from(status, title, meetingId, dateFrom, dateTo);
            pageable = boundedPageable(page, size);
        } catch (IllegalArgumentException error) {
            searchMetrics.recordValidationDenied();
            throw error;
        }
        Page<MeetingResponse> result = meetingService.listMeetings(tenant, criteria, pageable);
        searchMetrics.recordSuccess(criteria);
        return new PagedResponse<>(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/{id}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public MeetingResponse get(@PathVariable UUID id) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.getMeeting(tenant, id);
    }

    @PostMapping
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<MeetingResponse> create(@Valid @RequestBody MeetingCreateRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        MeetingResponse created = meetingService.createMeeting(tenant, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public MeetingResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody MeetingUpdateRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.updateMeeting(tenant, id, request);
    }

    @DeleteMapping("/{id}")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        meetingService.deleteMeeting(tenant, id);
        return ResponseEntity.noContent().build();
    }

    static Pageable boundedPageable(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be zero or greater.");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1.");
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
    }
}
