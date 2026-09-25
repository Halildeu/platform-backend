package com.example.meeting.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.*;
import com.example.meeting.dto.MeetingRecordingAccessResponse;
import com.example.meeting.security.*;
import com.example.meeting.service.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TeamsCalendarController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, MeetingWebMvcConfig.class, TeamsCalendarBridgeProperties.class})
class TeamsCalendarControllerTest {
    static final String SUBJECT = "df8e12a5-3182-42a6-ab65-80817ab48417";
    static final String ISSUER = "https://issuer.example/realms/platform";
    static final UUID MEETING = UUID.fromString("11111111-2222-4333-8444-555555555555");
    static final UUID ORG = UUID.fromString("aaaaaaaa-2222-4333-8444-555555555555");
    static final UUID MS_TENANT = UUID.fromString("bbbbbbbb-2222-4333-8444-555555555555");
    static final UUID ORGANIZER = UUID.fromString("cccccccc-2222-4333-8444-555555555555");
    static final String ROOT = "/api/v1/admin/meetings/" + MEETING + "/teams-calendar";
    static final AdminTenantContext CONTEXT = new AdminTenantContext(ORG, SUBJECT, SUBJECT);
    final OffsetDateTime from = OffsetDateTime.now().plusHours(1), to = from.plusDays(1);
    @Autowired MockMvc mvc;
    @Autowired TeamsCalendarBridgeProperties properties;
    @MockitoBean MeetingService meetings;
    @MockitoBean TenantContextResolver tenants;
    @MockitoBean TeamsCalendarTransport transport;
    @MockitoBean OpenFgaAuthzService authz;

    @BeforeEach void setup() {
        properties.setEnabled(true); properties.setControlKey("x".repeat(32)); properties.setMicrosoftTenantId(MS_TENANT.toString());
        when(authz.isEnabled()).thenReturn(true);
        when(authz.check(SUBJECT, MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE)).thenReturn(true);
        when(tenants.resolveRequired()).thenReturn(CONTEXT);
        when(meetings.requireRecordingAccess(CONTEXT, MEETING)).thenReturn(new MeetingRecordingAccessResponse(MEETING, ORG, ORG, List.of()));
        when(meetings.requireTeamsSchedulingAccess(CONTEXT, MEETING)).thenReturn(new MeetingRecordingAccessResponse(MEETING, ORG, ORG, List.of()));
        when(transport.resolve(ISSUER, SUBJECT)).thenReturn(identity(SUBJECT, MS_TENANT, 35));
    }
    private TeamsCalendarTransport.Organizer identity(String subject, UUID tenant, long company) {
        return new TeamsCalendarTransport.Organizer(7, company, subject, tenant, ORGANIZER);
    }
    private TeamsCalendarTransport.Schedule schedule(UUID meeting) {
        return new TeamsCalendarTransport.Schedule(meeting, "pending", from, to, null);
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return jwt().jwt(j -> j.issuer(ISSUER).subject(SUBJECT).claim("companyId", "35"))
                .authorities(new SimpleGrantedAuthority("SCOPE_meeting"));
    }
    @Test void anonymousCannotReadOrWrite() throws Exception {
        mvc.perform(get(ROOT + "/schedule")).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT + "/schedule").contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"AAMk\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(transport, meetings);
    }
    @Test void missingScopeCannotReachDirectory() throws Exception {
        mvc.perform(get(ROOT + "/schedule").with(jwt().jwt(j -> j.issuer(ISSUER).subject(SUBJECT))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transport, meetings);
    }
    @Test void moduleReadPermissionDoesNotAuthorizeScheduling() throws Exception {
        when(authz.check(SUBJECT, MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE)).thenReturn(false);
        mvc.perform(get(ROOT + "/schedule").with(user())).andExpect(status().isForbidden());
        verifyNoInteractions(transport, meetings);
    }
    @ParameterizedTest @ValueSource(ints = {403,404,503})
    void objectAuthorizationFailureStopsBeforeDirectory(int status) throws Exception {
        when(meetings.requireRecordingAccess(CONTEXT, MEETING)).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status)));
        mvc.perform(get(ROOT + "/schedule").with(user())).andExpect(status().is(status));
        verifyNoInteractions(transport);
    }
    @Test void disabledIsUnavailableNotAnEmptyCalendar() throws Exception {
        properties.setEnabled(false);
        mvc.perform(get(ROOT + "/schedule").with(user())).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(transport);
    }
    @Test void cancelledOrCompletedMeetingCannotReceiveNewSelection() throws Exception {
        when(meetings.requireTeamsSchedulingAccess(CONTEXT, MEETING)).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        mvc.perform(post(ROOT + "/schedule").with(user()).contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"AAMk\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(transport);
    }
    @Test void bodyOrganizerAndTimeCannotOverrideVerifiedIdentityOrGraphEvent() throws Exception {
        when(transport.select(ORGANIZER, MEETING, "AAMk+/=")).thenReturn(schedule(MEETING));
        mvc.perform(post(ROOT + "/schedule").with(user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"AAMk+/=\",\"organizerId\":\"someone-else\",\"startsAt\":\"tomorrow\",\"joinUrl\":\"https://evil.example\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meetingId").value(MEETING.toString())).andExpect(jsonPath("$.organizerId").doesNotExist());
        verify(transport).select(ORGANIZER, MEETING, "AAMk+/=");
    }
    @Test void statusAndCancelUseResolvedOwner() throws Exception {
        when(transport.status(ORGANIZER, MEETING)).thenReturn(schedule(MEETING));
        mvc.perform(get(ROOT + "/schedule").with(user())).andExpect(status().isOk());
        mvc.perform(delete(ROOT + "/schedule").with(user())).andExpect(status().isNoContent());
        verify(transport).status(ORGANIZER, MEETING); verify(transport).cancel(ORGANIZER, MEETING);
        verify(meetings, times(2)).requireRecordingAccess(CONTEXT, MEETING);
    }
    @ParameterizedTest @ValueSource(strings = {"subject", "tenant", "company"})
    void foreignDirectoryIdentityIsDenied(String field) throws Exception {
        when(transport.resolve(ISSUER, SUBJECT)).thenReturn(identity(field.equals("subject") ? UUID.randomUUID().toString() : SUBJECT,
                field.equals("tenant") ? UUID.randomUUID() : MS_TENANT, field.equals("company") ? 36 : 35));
        mvc.perform(delete(ROOT + "/schedule").with(user())).andExpect(status().isForbidden());
        verify(transport, never()).cancel(any(), any());
    }
    @Test void canonicalOrganizationIsNotComparedToHashOfCompanyNumber() throws Exception {
        when(transport.status(ORGANIZER, MEETING)).thenReturn(schedule(MEETING));
        mvc.perform(get(ROOT + "/schedule").with(user())).andExpect(status().isOk());
    }
    @Test void contextSubjectMismatchStopsBeforeMeeting() throws Exception {
        when(tenants.resolveRequired()).thenReturn(new AdminTenantContext(ORG, "different-subject", SUBJECT));
        mvc.perform(get(ROOT + "/schedule").with(user())).andExpect(status().isForbidden());
        verifyNoInteractions(transport, meetings);
    }
    @Test void impersonationCannotScheduleAsAnotherPerson() throws Exception {
        mvc.perform(get(ROOT + "/schedule").with(jwt().jwt(j -> j.issuer(ISSUER).subject(SUBJECT).claim("azp", "impersonation-broker"))
                .authorities(new SimpleGrantedAuthority("SCOPE_meeting")))).andExpect(status().isForbidden());
        verifyNoInteractions(transport, meetings);
    }
    @Test void foreignMeetingResponseIsNotExposed() throws Exception {
        when(transport.status(ORGANIZER, MEETING)).thenReturn(schedule(UUID.randomUUID()));
        mvc.perform(get(ROOT + "/schedule").with(user())).andExpect(status().isServiceUnavailable());
    }
    @Test void listIsBoundedReadOnlyAndReportsTruncation() throws Exception {
        when(transport.browse(any(), any(), any())).thenReturn(new TeamsCalendarTransport.Choices(
                List.of(new TeamsCalendarTransport.Choice("AAMk", "Müşteri sunumu", from, to)), true));
        mvc.perform(post(ROOT + "/events").with(user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.items[0].title").value("Müşteri sunumu"));
        verify(transport, never()).select(any(), any(), any());
    }
    @Test void overlongWindowNeverReachesWorker() throws Exception {
        mvc.perform(post(ROOT + "/events").with(user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"" + from + "\",\"to\":\"" + from.plusDays(32) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(transport, never()).browse(any(), any(), any());
    }
    @Test void pathLikeEventNeverReachesWorker() throws Exception {
        mvc.perform(post(ROOT + "/schedule").with(user()).contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":\"../path\"}"))
                .andExpect(status().isBadRequest());
        verify(transport, never()).select(any(), any(), any());
    }
}
