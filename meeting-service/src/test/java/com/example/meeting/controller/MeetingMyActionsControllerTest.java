package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.meeting.config.SecurityConfigLocal;
import com.example.meeting.dto.v1.admin.MyMeetingActionResponse;
import com.example.meeting.model.MeetingActionStatus;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** "Görevlerim" endpoint — Faz 24 Görevler dilim-1 (gitops#3487). */
@WebMvcTest(MeetingMyActionsController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class MeetingMyActionsControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ACTION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MeetingService meetingService;
    @MockitoBean private TenantContextResolver tenantContextResolver;

    @Test
    void listMyActions_withoutFilter_delegatesWithNullStatusSet() throws Exception {
        AdminTenantContext tenant = tenant();
        when(meetingService.listMyActions(eq(tenant), isNull()))
                .thenReturn(List.of(response()));

        mockMvc.perform(get("/api/v1/admin/my/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ACTION_ID.toString()))
                .andExpect(jsonPath("$[0].meetingTitle").value("Bütçe toplantısı"))
                .andExpect(jsonPath("$[0].assigneeSubject").value("ali"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));

        verify(meetingService).listMyActions(eq(tenant), isNull());
    }

    @Test
    void listMyActions_statusParamsAreForwarded() throws Exception {
        AdminTenantContext tenant = tenant();
        when(meetingService.listMyActions(eq(tenant), eq(EnumSet.of(MeetingActionStatus.DONE))))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/my/actions").param("status", "DONE"))
                .andExpect(status().isOk());

        verify(meetingService).listMyActions(eq(tenant), eq(EnumSet.of(MeetingActionStatus.DONE)));
    }

    private AdminTenantContext tenant() {
        AdminTenantContext tenant = new AdminTenantContext(TENANT_ID, "ali", "ali");
        when(tenantContextResolver.resolveRequired()).thenReturn(tenant);
        return tenant;
    }

    private static MyMeetingActionResponse response() {
        Instant now = Instant.parse("2026-08-24T07:00:00Z");
        return new MyMeetingActionResponse(
                ACTION_ID, MEETING_ID, "Bütçe toplantısı", TENANT_ID,
                "Raporu gönder", "ali", MeetingActionStatus.OPEN,
                Instant.parse("2026-08-25T09:00:00Z"),
                "c@e.com", now, "c@e.com", now, 0L);
    }
}
