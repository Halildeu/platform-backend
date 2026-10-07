package com.example.auditconsumer.bot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BotRecordingController.class, properties = "audit.bot-recording.enabled=true")
@Import({BotRecordingSecurity.class, BotRecordingHttpSecurityTest.Keys.class})
class BotRecordingHttpSecurityTest {
    @TestConfiguration static class Keys {
        @Bean @Primary JwtDecoder testSignedDecoder() throws Exception { return BotRecordingSecurityTest.decoder(); }
    }
    @Autowired MockMvc mvc;
    @MockitoBean BotRecordingOwner owner;
    // Override production key discovery, while the selected decoder still verifies real signatures/claims.
    @MockitoBean(name = "botRecordingDecoder") JwtDecoder unusedDiscovery;

    @BeforeEach void resetOwner() { clearInvocations(owner); }
    @Test void unauthenticatedAndWrongPermissionNeverReachOwner() throws Exception {
        mvc.perform(post("/api/v1/internal/bot-recording/grant").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/bot-recording/grant").contentType("application/json").content("{}")
                        .header("Authorization", "Bearer " + BotRecordingSecurityTest.token(b -> b.claim("perm", java.util.List.of("meeting:teams-schedule:authorize")))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(owner);
    }
    @Test void exactSignedTokenReachesOwnerAndResponseIsNotCached() throws Exception {
        mvc.perform(post("/api/v1/internal/bot-recording/grant").contentType("application/json").content("{}")
                        .header("Authorization", "Bearer " + BotRecordingSecurityTest.token(b -> {})))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(owner).grant(any());
    }
    @Test void authenticatedServiceCannotUseUnrelatedApplicationRoutes() throws Exception {
        mvc.perform(post("/api/v1/admin/anything").header("Authorization", "Bearer " + BotRecordingSecurityTest.token(b -> {})))
                .andExpect(status().isForbidden());
        verifyNoInteractions(owner);
    }
}
