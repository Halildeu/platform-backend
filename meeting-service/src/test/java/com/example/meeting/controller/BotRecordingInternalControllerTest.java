package com.example.meeting.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.service.BotRecordingService;
import java.time.Instant;
import java.util.List;
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

@WebMvcTest(controllers = BotRecordingInternalController.class)
@ActiveProfiles("test") @Import(SecurityConfig.class)
class BotRecordingInternalControllerTest {
    private final UUID meeting = UUID.randomUUID();
    @Autowired MockMvc mvc;
    @MockitoBean BotRecordingService service;
    @MockitoBean OpenFgaAuthzService authz;
    @MockitoBean JwtDecoder jwtDecoder;
    @Test void missingTokenCannotReachService() throws Exception { send(false, 401); verifyNoInteractions(service); }
    @Test void exactWorkerAdmissionPermissionPassesActualConverterAndMethodGate() throws Exception {
        token("auth-service", "teams-capture-worker", "teams-capture-worker", "meeting:bot-recording:admit");
        send(true, 200); verify(service).admit(eq(meeting), any());
    }
    @ParameterizedTest @ValueSource(strings = {"meeting:teams-schedule:authorize", "meeting:session:resolve", "audit:bot-recording:manage"})
    void otherPermissionsCannotReachAdmission(String permission) throws Exception {
        token("auth-service", "teams-capture-worker", "teams-capture-worker", permission); send(true, 403); verifyNoInteractions(service);
    }
    @Test void userPermissionClaimAndOtherServiceIdentityCannotAdmit() throws Exception {
        token("https://user.example", "teams-capture-worker", "teams-capture-worker", "meeting:bot-recording:admit"); send(true, 403);
        token("auth-service", "meeting-ai", "meeting-ai", "meeting:bot-recording:admit"); send(true, 403);
        token("auth-service", "teams-capture-worker", "meeting-ai", "meeting:bot-recording:admit"); send(true, 403);
        verifyNoInteractions(service);
    }
    private void send(boolean bearer, int expected) throws Exception {
        var request = post("/api/v1/internal/meetings/" + meeting + "/bot-recording/admit").contentType(MediaType.APPLICATION_JSON).content("{}");
        if (bearer) request.header("Authorization", "Bearer verified");
        mvc.perform(request).andExpect(status().is(expected));
    }
    private void token(String issuer, String subject, String client, String permission) {
        when(jwtDecoder.decode("verified")).thenReturn(Jwt.withTokenValue("verified").header("alg", "RS256").issuer(issuer).subject(subject)
                .claim("client_id", client).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("perm", List.of(permission)).build());
    }
}
