package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.meeting.config.SecurityConfigLocal;
import com.example.meeting.dto.v1.admin.MeetingResponse;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer (MockMvc slice) test for {@link MeetingAdminController} —
 * Faz 24 (#410). Uses the endpoint-admin controller-test convention:
 * {@code @WebMvcTest} + {@code @ActiveProfiles("local")} +
 * {@code @Import(SecurityConfigLocal.class)} so the permitAll local chain
 * is active and the {@code @RequireModule} interceptor (a {@code !local}
 * bean) is NOT registered — the route wiring, request mapping, validation,
 * status codes, and JSON projection are exercised against a mocked
 * service + tenant resolver.
 */
@WebMvcTest(MeetingAdminController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class MeetingAdminControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeetingService meetingService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    private AdminTenantContext tenant() {
        AdminTenantContext ctx = new AdminTenantContext(TENANT_ID, "admin@example.com", "admin@example.com");
        when(tenantContextResolver.resolveRequired()).thenReturn(ctx);
        return ctx;
    }

    private MeetingResponse sampleResponse() {
        return new MeetingResponse(
                MEETING_ID, TENANT_ID, "weekly sync", "desc",
                MeetingStatus.SCHEDULED,
                Instant.parse("2026-06-16T09:00:00Z"),
                Instant.parse("2026-06-16T10:00:00Z"),
                "organizer@example.com", "admin@example.com",
                Instant.parse("2026-06-16T08:00:00Z"),
                "admin@example.com",
                Instant.parse("2026-06-16T08:00:00Z"),
                0L);
    }

    @Test
    void list_returnsPagedEnvelopeScopedToTenant() throws Exception {
        AdminTenantContext ctx = tenant();
        Page<MeetingResponse> page = new PageImpl<>(List.of(sampleResponse()));
        when(meetingService.listMeetings(eq(ctx), any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/meetings").param("page", "0").param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(MEETING_ID.toString()))
                .andExpect(jsonPath("$.content[0].title").value("weekly sync"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(meetingService).listMeetings(eq(ctx), any(), any(Pageable.class));
    }

    @Test
    void list_invalidStatusEnum_returns400() throws Exception {
        tenant();
        // status=BOGUS → MethodArgumentTypeMismatchException → 400 via the handler.
        mockMvc.perform(get("/api/v1/admin/meetings").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    @Test
    void get_returnsSingleMeeting() throws Exception {
        AdminTenantContext ctx = tenant();
        when(meetingService.getMeeting(ctx, MEETING_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/admin/meetings/{id}", MEETING_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(MEETING_ID.toString()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    @Test
    void create_returns201Created() throws Exception {
        AdminTenantContext ctx = tenant();
        when(meetingService.createMeeting(eq(ctx), any())).thenReturn(sampleResponse());

        String body = """
                {"title":"weekly sync","description":"desc",
                 "scheduledStart":"2026-06-16T09:00:00Z"}
                """;

        mockMvc.perform(post("/api/v1/admin/meetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(MEETING_ID.toString()));

        verify(meetingService).createMeeting(eq(ctx), any());
    }

    @Test
    void create_blankTitle_returns400Validation() throws Exception {
        tenant();
        String body = "{\"title\":\"\"}";

        mockMvc.perform(post("/api/v1/admin/meetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void delete_returns204() throws Exception {
        AdminTenantContext ctx = tenant();

        mockMvc.perform(delete("/api/v1/admin/meetings/{id}", MEETING_ID))
                .andExpect(status().isNoContent());

        verify(meetingService).deleteMeeting(ctx, MEETING_ID);
    }
}
