package com.example.meeting.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.meeting.dto.v1.admin.MyMeetingActionResponse;
import com.example.meeting.model.MeetingActionStatus;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Assignee-centric task list — Faz 24 Görevler dilim-1 (gitops#3487).
 *
 * <p>Deliberately NOT under {@code /meetings/{meetingId}}: the whole point is
 * the cross-meeting view of what the CALLER owns. The assignee is always the
 * authenticated subject from {@link AdminTenantContext} — there is no
 * {@code ?assignee=} parameter, so the endpoint cannot be used to enumerate
 * someone else's workload (module VIEWER would not be enough of a gate for
 * that; a person's task list is their own).
 */
@RestController
@RequestMapping("/api/v1/admin/my")
public class MeetingMyActionsController {

    private final MeetingService meetingService;
    private final TenantContextResolver tenantContextResolver;

    public MeetingMyActionsController(
            MeetingService meetingService,
            TenantContextResolver tenantContextResolver) {
        this.meetingService = meetingService;
        this.tenantContextResolver = tenantContextResolver;
    }

    /**
     * No {@code status} filter means the ACTIVE set (OPEN + IN_PROGRESS);
     * pass {@code ?status=DONE} (repeatable) to page through the closed tail.
     */
    @GetMapping("/actions")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public List<MyMeetingActionResponse> listMyActions(
            @RequestParam(name = "status", required = false) Set<MeetingActionStatus> statuses) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingService.listMyActions(tenant, statuses);
    }
}
