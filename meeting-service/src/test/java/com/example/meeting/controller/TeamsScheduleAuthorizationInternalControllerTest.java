package com.example.meeting.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.service.TeamsScheduleAuthorizationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = TeamsScheduleAuthorizationInternalController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class TeamsScheduleAuthorizationInternalControllerTest {
    private static final UUID MEETING = UUID.randomUUID();
    private static final String PATH = "/api/v1/internal/meetings/" + MEETING + "/teams-calendar/authorize";
    @Autowired MockMvc mvc;
    @MockitoBean TeamsScheduleAuthorizationService service;
    @MockitoBean OpenFgaAuthzService authz;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test void noCredentialCannotReachAuthorization() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
    @Test void exactServicePermissionUsesRealConverterAndReturnsNoContentNoStore() throws Exception {
        token("auth-service", "meeting:teams-schedule:authorize");
        mvc.perform(post(PATH).header("Authorization", "Bearer verified").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        verify(service).authorize(MEETING, null, null);
    }
    @ParameterizedTest @ValueSource(strings = {"meeting:session:resolve", "meeting:analysis-result:write", "users:internal"})
    void otherServicePermissionsCannotAuthorizeDispatch(String permission) throws Exception {
        token("auth-service", permission);
        mvc.perform(post(PATH).header("Authorization", "Bearer verified").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()); verifyNoInteractions(service);
    }
    @Test void adminUserCannotForgeServicePermission() throws Exception {
        token("https://login.example/realms/platform", "meeting:teams-schedule:authorize");
        mvc.perform(post(PATH).header("Authorization", "Bearer verified").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()); verifyNoInteractions(service);
    }
    @Test void malformedBodyCannotReachService() throws Exception {
        token("auth-service", "meeting:teams-schedule:authorize");
        mvc.perform(post(PATH).header("Authorization", "Bearer verified").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()); verifyNoInteractions(service);
    }
    private void token(String issuer, String permission) {
        when(jwtDecoder.decode("verified")).thenReturn(Jwt.withTokenValue("verified").header("alg", "RS256")
                .issuer(issuer).subject("teams-capture-worker").claim("client_id", "teams-capture-worker").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("perm", List.of(permission)).claim("realm_access", Map.of("roles", List.of("admin"))).build());
    }
}
