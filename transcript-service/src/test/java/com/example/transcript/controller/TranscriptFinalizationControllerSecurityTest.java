package com.example.transcript.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.transcript.config.SecurityConfig;
import com.example.transcript.config.TranscriptWebMvcConfig;
import com.example.transcript.security.TenantContextResolver;
import com.example.transcript.security.TranscriptAuthz;
import com.example.transcript.service.TranscriptFinalizationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TranscriptFinalizationController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, TranscriptWebMvcConfig.class})
class TranscriptFinalizationControllerSecurityTest {

    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final String PATH =
            "/api/v1/admin/transcripts/meetings/{meetingId}/sessions/{sessionId}/finalizations";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TranscriptFinalizationService service;
    @MockitoBean private TenantContextResolver tenants;
    @MockitoBean private OpenFgaAuthzService authz;

    @Test
    void viewerWithoutManageCannotFinalize() throws Exception {
        when(authz.isEnabled()).thenReturn(true);
        when(authz.check("user-1", TranscriptAuthz.MANAGER, "module", TranscriptAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(post(PATH, MEETING, SESSION)
                        .with(jwt().jwt(token -> token.subject("user-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_transcript")))
                        .contentType("application/json")
                        .content("{\"finalizationVersion\":1}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
