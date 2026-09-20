package com.example.transcript.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.common.meeting.speakers.SpeakerLabels;
import com.example.transcript.config.SecurityConfig;
import com.example.transcript.config.SpeakerLabelBodyLimitFilter;
import com.example.transcript.service.CanonicalSpeakerLabelService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(CanonicalSpeakerLabelController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, SpeakerLabelBodyLimitFilter.class})
class CanonicalSpeakerLabelSecurityTest {
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String PATH = "/api/v1/internal/tenants/" + ID + "/meetings/" + ID
            + "/sessions/" + ID + "/finalizations/1/speaker-labels";
    private static final String BODY = "{\"scope\":\"" + ID + "\",\"speaker\":\"S1\",\"name\":\"Zeynep\",\"expectedRevision\":0}";
    @Autowired MockMvc mvc;
    @MockitoBean CanonicalSpeakerLabelService service;
    @MockitoBean com.example.commonauth.openfga.OpenFgaAuthzService authz;
    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request, String permission) {
        return request.with(jwt().jwt(t -> t.subject("meeting-service"))
                .authorities(new SimpleGrantedAuthority("SVC_" + permission)))
                .header("X-Tenant-Id", ID).header("X-Analysis-Run-Id", ID)
                .header("X-Analysis-Spec-Version", "meeting-intelligence-v1").header("X-Actor-Subject", "owner");
    }
    @Test void canonicalReaderCannotReadUserNamesOrWriteThem() throws Exception {
        mvc.perform(headers(get(PATH), "transcript:canonical:read")).andExpect(status().isForbidden());
        mvc.perform(headers(put(PATH), "transcript:canonical:read").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void readerCannotWriteAndWriterCannotRead() throws Exception {
        mvc.perform(headers(get(PATH), "transcript:speaker-label:write")).andExpect(status().isForbidden());
        mvc.perform(headers(put(PATH), "transcript:speaker-label:read").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void dedicatedReadReturnsNonCachedNamesAndForwardsExactActorAndTuple() throws Exception {
        when(service.read(ID, ID, ID, 1, ID, ID, "meeting-intelligence-v1", "owner"))
                .thenReturn(new SpeakerLabels.Snapshot(ID, ID, ID, 1, ID, "a".repeat(64), 1, true,
                        List.of(new SpeakerLabels.Label(ID, "S1", "Zeynep"))));
        mvc.perform(headers(get(PATH), "transcript:speaker-label:read"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.labels[0].name").value("Zeynep"));
        verify(service).read(ID, ID, ID, 1, ID, ID, "meeting-intelligence-v1", "owner");
    }
    @Test void invalidNamesAndOversizedBodyNeverReachStorage() throws Exception {
        mvc.perform(headers(put(PATH), "transcript:speaker-label:write").contentType("application/json")
                .content(BODY.replace("Zeynep", "x".repeat(81)))).andExpect(status().isBadRequest());
        mvc.perform(headers(put(PATH), "transcript:speaker-label:write").contentType("application/json")
                .content(" ".repeat(4097))).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(service);
    }
}
