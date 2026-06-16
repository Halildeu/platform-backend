package com.example.transcript.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.transcript.config.SecurityConfigLocal;
import com.example.transcript.dto.TranscriptSegmentDto;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.security.AdminTenantContext;
import com.example.transcript.security.TenantContextResolver;
import com.example.transcript.service.TranscriptExportService;
import com.example.transcript.service.TranscriptSegmentService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc slice test for {@link AdminTranscriptController} request/response
 * wiring under the local-profile auth bypass (the OpenFGA interceptor is not
 * active under {@code local}; the dedicated 401/403 coverage lives in
 * {@link AdminTranscriptControllerSecurityTest}). Mirrors endpoint-admin's
 * {@code AdminEndpointDeviceHealthControllerTest} shape.
 */
@WebMvcTest(AdminTranscriptController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminTranscriptControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEGMENT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MEETING = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptSegmentService segmentService;
    @MockitoBean
    private TranscriptExportService exportService;
    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @BeforeEach
    void setUp() {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, "admin@example.com"));
    }

    @Test
    void getSegment_returns200() throws Exception {
        when(segmentService.getSegment(any(), eq(SEGMENT))).thenReturn(dto());

        mockMvc.perform(get("/api/v1/admin/transcripts/{id}", SEGMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SEGMENT.toString()))
                .andExpect(jsonPath("$.textDraft").value("hello"));
    }

    @Test
    void list_byMeeting_returnsPage() throws Exception {
        Page<TranscriptSegmentDto> page =
                new PageImpl<>(List.of(dto()), PageRequest.of(0, 50), 1);
        when(segmentService.listByMeeting(any(), eq(MEETING), eq(0), eq(0))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/transcripts").param("meetingId", MEETING.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(SEGMENT.toString()));
    }

    @Test
    void list_requiresExactlyOneOfMeetingOrSession_400WhenBothMissing() throws Exception {
        mockMvc.perform(get("/api/v1/admin/transcripts"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_requiresExactlyOneOfMeetingOrSession_400WhenBothGiven() throws Exception {
        mockMvc.perform(get("/api/v1/admin/transcripts")
                        .param("meetingId", MEETING.toString())
                        .param("sessionId", SESSION.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_returnsPage() throws Exception {
        Page<TranscriptSegmentDto> page =
                new PageImpl<>(List.of(dto()), PageRequest.of(0, 50), 1);
        when(segmentService.search(any(), eq("budget"), eq(MEETING), eq(0), eq(0))).thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/transcripts/search")
                        .param("query", "budget")
                        .param("meetingId", MEETING.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void export_jsonFormat_returnsDtoList_andInvokesAuditedServicePath() throws Exception {
        when(segmentService.exportByMeeting(any(), eq(MEETING))).thenReturn(List.of(dto()));

        mockMvc.perform(get("/api/v1/admin/transcripts/export")
                        .param("meetingId", MEETING.toString())
                        .param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(SEGMENT.toString()));

        // The JSON export goes through the audited service path (which writes
        // the KVKK m.12 EXPORT row).
        verify(segmentService).exportByMeeting(any(), eq(MEETING));
    }

    @Test
    void create_returns201() throws Exception {
        when(segmentService.create(any(), any())).thenReturn(dto());

        String body = "{\"meetingId\":\"" + MEETING + "\",\"startTime\":0.0,\"endTime\":1.0,"
                + "\"textDraft\":\"hello\"}";
        mockMvc.perform(post("/api/v1/admin/transcripts")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SEGMENT.toString()));
    }

    private static TranscriptSegmentDto dto() {
        return new TranscriptSegmentDto(
                SEGMENT, TENANT, MEETING, SESSION, null, 0.0, 1.0,
                "hello", null, 0.9, TranscriptSegmentStatus.DRAFT, 0L,
                Instant.parse("2026-06-16T10:00:00Z"), Instant.parse("2026-06-16T10:00:00Z"));
    }
}
