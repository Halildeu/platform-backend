package com.example.meeting.service;

import com.example.meeting.config.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpTeamsCalendarTransportTest {
    final UUID organizer = UUID.randomUUID(), meeting = UUID.randomUUID();
    final TeamsCalendarBridgeProperties properties = new TeamsCalendarBridgeProperties();
    final MeetingAssigneeDirectoryProperties directory = new MeetingAssigneeDirectoryProperties();
    final AssigneeDirectoryTokenProvider tokens = mock(AssigneeDirectoryTokenProvider.class);
    MockRestServiceServer server;
    HttpTeamsCalendarTransport client;
    @BeforeEach void setup() {
        properties.setEnabled(true); properties.setControlKey("x".repeat(32)); properties.setMicrosoftTenantId(UUID.randomUUID().toString());
        directory.setEnabled(true);
        RestClient.Builder builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpTeamsCalendarTransport(properties, directory, tokens, builder.build(), JsonMapper.builder().findAndAddModules().build());
    }
    @Test void identityUsesOnlyExactSubjectAndServiceToken() {
        when(tokens.token()).thenReturn("synthetic-directory");
        server.expect(requestTo(directory.getUserServiceBaseUrl() + "/api/users/internal/microsoft-organizer/resolve"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer synthetic-directory"))
                .andExpect(headerDoesNotExist("X-Teams-Control-Key"))
                .andExpect(jsonPath("$.subject").value("exact-subject")).andExpect(jsonPath("$.issuer").value("https://issuer.example"))
                .andRespond(withSuccess("{\"userId\":7,\"companyId\":35,\"subject\":\"exact-subject\",\"tenantId\":\"" + properties.getMicrosoftTenantId()
                        + "\",\"organizerId\":\"" + organizer + "\"}", MediaType.APPLICATION_JSON));
        assertThat(client.resolve("https://issuer.example", "exact-subject").organizerId()).isEqualTo(organizer); server.verify();
    }
    @Test void selectionSendsNoBrowserTokenAndUsesStableCorrelation() {
        String result = "{\"meetingId\":\"" + meeting + "\",\"state\":\"pending\",\"startsAt\":\"2026-09-24T12:00:00Z\",\"endsAt\":\"2026-09-24T13:00:00Z\",\"callId\":\"private\"}";
        for (int i = 0; i < 2; i++) server.expect(requestTo(properties.getWorkerBaseUrl() + "/api/teams/meetings/" + meeting + "/calendar-schedule"))
                .andExpect(header("X-Teams-Control-Key", properties.getControlKey())).andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.organizerId").value(organizer.toString())).andExpect(jsonPath("$.eventId").value("AAMk+/="))
                .andExpect(jsonPath("$.actor.organizationId").value(meeting.toString())).andExpect(jsonPath("$.actor.subject").value("subject"))
                .andExpect(jsonPath("$.correlationId").value("teams-calendar-" + meeting)).andRespond(withSuccess(result, MediaType.APPLICATION_JSON));
        assertThat(client.select(organizer, meeting, "AAMk+/=", new TeamsScheduleActor(1, "https://issuer.example", "subject", meeting, organizer, "subject", 7, 35)).meetingId()).isEqualTo(meeting);
        client.select(organizer, meeting, "AAMk+/=", new TeamsScheduleActor(1, "https://issuer.example", "subject", meeting, organizer, "subject", 7, 35)); server.verify();
    }
    @Test void cancelUsesOwnerScopedRouteAndRequiresNoContent() {
        server.expect(requestTo(ownedPath())).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        client.cancel(organizer, meeting); server.verify();
    }
    @ParameterizedTest @ValueSource(ints = {301,302,401,403,404,409,429,500})
    void failureDoesNotReflectUpstreamBodyOrFollowRedirect(int status) {
        server.expect(requestTo(ownedPath())).andRespond(withStatus(HttpStatus.valueOf(status))
                .header("Location", "https://untrusted.example/").body("secret data"));
        assertThatThrownBy(() -> client.status(organizer, meeting)).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getReason()).doesNotContain("secret", "untrusted");
            assertThat(error.getStatusCode().value()).isEqualTo(status == 403 || status == 404 || status == 409 ? status : 503);
        }); server.verify();
    }
    @ParameterizedTest @ValueSource(strings = {"invalid", "{}garbage"})
    void malformedResponseIsUnavailable(String body) {
        server.expect(requestTo(ownedPath())).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.status(organizer, meeting)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void oversizedResponseIsUnavailable() {
        server.expect(requestTo(ownedPath())).andRespond(withSuccess(" ".repeat(1024 * 1024 + 1), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.status(organizer, meeting)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void disabledMakesNoWorkerRequest() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> client.browse(organizer, OffsetDateTime.now(), OffsetDateTime.now().plusDays(1)))
                .isInstanceOf(ResponseStatusException.class); server.verify();
    }
    private String ownedPath() { return properties.getWorkerBaseUrl() + "/api/teams/organizers/" + organizer + "/meetings/" + meeting + "/calendar-schedule"; }
}
