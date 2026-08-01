package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.meeting.config.SecurityConfigLocal;
import com.example.meeting.dto.v1.admin.MeetingAgendaItemResponse;
import com.example.meeting.model.MeetingAgendaItemStatus;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeetingSubResourceController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class MeetingSubResourceControllerAgendaTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID AGENDA_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MeetingService meetingService;
    @MockitoBean private TenantContextResolver tenantContextResolver;

    @Test
    void createAgendaItemReturnsCanonicalProjection() throws Exception {
        AdminTenantContext tenant = tenant();
        when(meetingService.createAgendaItem(eq(tenant), eq(MEETING_ID), any()))
                .thenReturn(response());

        mockMvc.perform(post("/api/v1/admin/meetings/{meetingId}/agenda-items", MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"title":"Bütçe sapmaları",
                                 "ownerSubject":"finance-owner","plannedDurationMinutes":20}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(AGENDA_ID.toString()))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(meetingService).createAgendaItem(eq(tenant), eq(MEETING_ID), any());
    }

    @Test
    void invalidPositionAndDurationFailBeforeService() throws Exception {
        tenant();

        mockMvc.perform(post("/api/v1/admin/meetings/{meetingId}/agenda-items", MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":-1,"title":"Geçersiz","plannedDurationMinutes":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verifyNoInteractions(meetingService);
    }

    private AdminTenantContext tenant() {
        AdminTenantContext tenant = new AdminTenantContext(TENANT_ID, "organizer", "organizer");
        when(tenantContextResolver.resolveRequired()).thenReturn(tenant);
        return tenant;
    }

    private static MeetingAgendaItemResponse response() {
        Instant now = Instant.parse("2026-08-01T07:00:00Z");
        return new MeetingAgendaItemResponse(
                AGENDA_ID,
                MEETING_ID,
                TENANT_ID,
                0,
                "Bütçe sapmaları",
                null,
                "finance-owner",
                20,
                MeetingAgendaItemStatus.PENDING,
                "organizer",
                now,
                "organizer",
                now,
                0L);
    }
}
