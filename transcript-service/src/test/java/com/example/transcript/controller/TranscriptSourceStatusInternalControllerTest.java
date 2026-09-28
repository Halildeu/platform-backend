package com.example.transcript.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.common.meeting.events.RecordingOutcome;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.transcript.config.SecurityConfig;
import com.example.transcript.dto.TranscriptSourceStatusDto;
import com.example.transcript.security.TenantContextResolver;
import com.example.transcript.service.TranscriptSourceStatusService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TranscriptSourceStatusInternalController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class TranscriptSourceStatusInternalControllerTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final String PATH =
            "/api/v1/internal/tenants/{tenant}/meetings/{meeting}/sessions/{session}/source-status";

    @Autowired private MockMvc mvc;
    @MockitoBean private TranscriptSourceStatusService service;
    @MockitoBean private TenantContextResolver tenants;
    @MockitoBean private OpenFgaAuthzService authz;

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get(PATH, TENANT, MEETING, SESSION).header("X-Tenant-Id", TENANT))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_ADMIN", "SCOPE_transcript", "SVC_transcript:session:erase",
            "SVC_transcript:analysis-job-capability:issue"})
    void otherPermissionsCannotReadStatus(String authority) throws Exception {
        mvc.perform(get(PATH, TENANT, MEETING, SESSION).header("X-Tenant-Id", TENANT)
                        .with(jwt().authorities(new SimpleGrantedAuthority(authority))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void canonicalReadReturnsScopedUncachedMetadataWithoutCapability() throws Exception {
        when(service.read(TENANT, MEETING, SESSION, TENANT, "meeting-service"))
                .thenReturn(new TranscriptSourceStatusDto(TENANT, MEETING, SESSION,
                        TranscriptSourceStatusDto.State.UNKNOWN, null, null, Instant.now(), null,
                        RecordingOutcome.UNKNOWN, null, null));
        mvc.perform(get(PATH, TENANT, MEETING, SESSION).header("X-Tenant-Id", TENANT)
                        .with(jwt().jwt(token -> token.subject("meeting-service"))
                                .authorities(new SimpleGrantedAuthority("SVC_transcript:canonical:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.meetingId").value(MEETING.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.state").value("UNKNOWN"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().doesNotExist("X-Analysis-Job-Capability"))
                .andExpect(header().doesNotExist("X-Analysis-Job-Capability-Expires-At"));
    }

    @Test
    void requiredTenantHeaderIsValidatedBeforeService() throws Exception {
        mvc.perform(get(PATH, TENANT, MEETING, SESSION)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SVC_transcript:canonical:read"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
