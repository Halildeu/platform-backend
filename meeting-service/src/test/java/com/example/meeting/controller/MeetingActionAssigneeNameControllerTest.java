package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.MeetingWebMvcConfig;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.dto.v1.admin.MeetingActionResponse;
import com.example.meeting.model.MeetingActionStatus;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.ActionAssigneeNames;
import com.example.meeting.service.MeetingService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** gitops#3834 — the action list goes out with the assignee's name, after the service returns. */
@WebMvcTest(MeetingSubResourceController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, MeetingWebMvcConfig.class})
class MeetingActionAssigneeNameControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SUBJECT = "kc-requester";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MeetingService meetingService;
    @MockitoBean private TenantContextResolver tenantContextResolver;
    @MockitoBean private OpenFgaAuthzService authzService;
    @MockitoBean private ActionAssigneeNames assigneeNames;

    @Test
    void listCarriesTheAssigneeName() throws Exception {
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.check(SUBJECT, MeetingAuthz.VIEWER, "module", MeetingAuthz.MODULE)).thenReturn(true);
        when(tenantContextResolver.resolveRequired()).thenReturn(new AdminTenantContext(TENANT_ID, SUBJECT, SUBJECT));
        MeetingActionResponse row = new MeetingActionResponse(UUID.randomUUID(), MEETING_ID, TENANT_ID,
                "Bütçe tablosunu kontrol et", "kc-7", null, MeetingActionStatus.OPEN, null,
                SUBJECT, Instant.EPOCH, SUBJECT, Instant.EPOCH, 0L);
        when(meetingService.listActions(any(AdminTenantContext.class), eq(MEETING_ID))).thenReturn(List.of(row));
        when(assigneeNames.withNames(anyList())).thenReturn(List.of(row.withAssigneeDisplayName("Sevil Kaya")));

        mockMvc.perform(get("/api/v1/admin/meetings/{meetingId}/actions", MEETING_ID)
                        .with(jwt().jwt(j -> j.subject(SUBJECT))
                                .authorities(new SimpleGrantedAuthority("SCOPE_meeting"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assigneeSubject").value("kc-7"))
                .andExpect(jsonPath("$[0].assigneeDisplayName").value("Sevil Kaya"));
    }
}
