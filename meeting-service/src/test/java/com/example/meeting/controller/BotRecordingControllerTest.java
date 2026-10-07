package com.example.meeting.controller;

import static com.example.common.meeting.bot.BotRecordingContract.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.*;
import com.example.meeting.dto.MeetingRecordingAccessResponse;
import com.example.meeting.security.*;
import com.example.meeting.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Real facade behind HTTP/security: browser-supplied authority never becomes stored consent. */
@WebMvcTest(controllers = BotRecordingController.class, properties = "SECURITY_JWT_ISSUER=https://user.example")
@ActiveProfiles("test")
@Import({SecurityConfig.class, BotRecordingService.class, BotRecordingProperties.class, TeamsCalendarBridgeProperties.class})
class BotRecordingControllerTest {
    private final UUID meeting = UUID.randomUUID(), org = UUID.randomUUID(), ms = UUID.randomUUID(), organizer = UUID.randomUUID(), intent = UUID.randomUUID();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired BotRecordingProperties properties;
    @Autowired TeamsCalendarBridgeProperties calendar;
    @MockitoBean BotRecordingTransport owner;
    @MockitoBean TenantContextResolver tenants;
    @MockitoBean MeetingService meetings;
    @MockitoBean TeamsCalendarTransport directory;
    @MockitoBean TeamsScheduleAuthorizationService dispatch;
    @MockitoBean OpenFgaAuthzService authz;
    @MockitoBean JwtDecoder jwtDecoder;
    @BeforeEach void setup() {
        properties.setEnabled(true); properties.setConsentText("Approved fixture terms"); properties.setConsentVersion("v1");
        calendar.setEnabled(true); calendar.setMicrosoftTenantId(ms.toString()); calendar.setControlKey("x".repeat(32));
        var context = new AdminTenantContext(org, "owner", "42"); when(tenants.resolveRequired()).thenReturn(context);
        when(authz.isEnabled()).thenReturn(true);
        when(authz.checkPrincipalFreshResult("user:42", "can_manage", "module", "MEETING"))
                .thenReturn(new OpenFgaAuthzService.CheckResult(true, "granted"));
        when(meetings.requireTeamsDispatchAccess(context, meeting)).thenReturn(new MeetingRecordingAccessResponse(meeting, org, org, List.of()));
        when(directory.resolve("https://user.example", "owner")).thenReturn(new TeamsCalendarTransport.Organizer(42, 35, "owner", ms, organizer));
        when(owner.findRequest(any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(owner.grant(any())).thenAnswer(call -> new Snapshot(1, "TEAMS_LIVE_TRANSCRIPTION", intent, call.getArgument(0), "GRANTED", 1, Instant.now(), null, null));
    }
    @Test void collectionGrantUsesShownTermsAndServerDerivedOwnerAndRedactsResponse() throws Exception {
        var shown = mvc.perform(get(path() + "/terms").with(user())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var terms = mapper.readTree(shown.getResponse().getContentAsString());
        var requestKey = UUID.randomUUID();
        var body = Map.of("requestKey", requestKey, "consentVersion", terms.get("version").asText(),
                "consentTextHash", terms.get("sha256").asText(), "locale", terms.get("locale").asText(),
                "expiresAt", Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS),
                "owner", Map.of("companyId", 999, "subject", "attacker"), "consent", true);
        mvc.perform(post(path()).with(user()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.intentId").value(intent.toString()))
                .andExpect(jsonPath("$.owner").doesNotExist()).andExpect(jsonPath("$.binding").doesNotExist())
                .andExpect(jsonPath("$.state").value("GRANTED"));
        verify(owner).grant(argThat(g -> g.owner().companyId() == 35 && g.owner().userId() == 42
                && g.owner().subject().equals("owner") && g.owner().organizerId().equals(organizer) && g.requestKey().equals(requestKey)));
    }
    @Test void publicUserPathChecksModuleExplicitlyEvenWithoutAdminInterceptor() throws Exception {
        when(authz.checkPrincipalFreshResult(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OpenFgaAuthzService.CheckResult(false, "no_relation"));
        mvc.perform(get(path() + "/terms").with(user())).andExpect(status().isForbidden()); verifyNoInteractions(owner, directory, meetings);
    }
    @Test void ownerWithdrawalStillWorksWithNewGrantsDisabledAndNoCurrentRecordingPermissions() throws Exception {
        properties.setEnabled(false); calendar.setEnabled(false);
        var actor = new Owner(35, 42, "https://user.example", "owner", org, org, "42", ms, organizer);
        var grant = new Grant(UUID.randomUUID(), meeting, actor, "old", "a".repeat(64), "tr-TR", Instant.now().minusSeconds(5));
        var snapshot = new Snapshot(1, "TEAMS_LIVE_TRANSCRIPTION", intent, grant, "GRANTED", 1, Instant.now().minusSeconds(60), null, null);
        when(owner.inspect(new IntentRef(intent, meeting))).thenReturn(snapshot);
        when(owner.revoke(any())).thenReturn(new Snapshot(1, snapshot.purpose(), intent, grant, "REVOKED", 2, snapshot.createdAt(), null, Instant.now()));
        mvc.perform(delete(path() + "/" + intent).with(user())).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REVOKED"));
        verifyNoInteractions(authz, tenants, meetings, directory, dispatch);
    }
    @Test void missingUserTokenCannotReadTerms() throws Exception {
        mvc.perform(get(path() + "/terms")).andExpect(status().isUnauthorized()); verifyNoInteractions(owner, tenants, authz);
    }
    private String path() { return "/api/v1/meetings/" + meeting + "/bot-recording-intents"; }
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor user() {
        return jwt().jwt(j -> j.issuer("https://user.example").subject("owner"));
    }
}
