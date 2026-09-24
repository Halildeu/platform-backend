package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.meeting.config.MeetingAssigneeDirectoryProperties;
import com.example.meeting.service.AssigneeDirectoryClient.AssigneeCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Faz 24 (gitops#3834) — the people-picker leg to user-service.
 *
 * <p>Two failure classes must stay apart: user-service saying "this requester is not an active
 * member" is a deny the user should see as such; any other refusal or outage is ours to fix and
 * must surface as unavailable — never as an empty list, which a picker shows as "nobody matches".
 */
@ExtendWith(MockitoExtension.class)
class HttpAssigneeDirectoryClientSearchCandidatesTest {

    private static final String URL = "http://user-service:8089/api/users/internal/assignee-candidates";

    @Mock private AssigneeDirectoryTokenProvider tokens;
    private MeetingAssigneeDirectoryProperties properties;
    private MockRestServiceServer server;
    private HttpAssigneeDirectoryClient client;

    @BeforeEach
    void setUp() {
        properties = new MeetingAssigneeDirectoryProperties();
        properties.setEnabled(true);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpAssigneeDirectoryClient(properties, tokens, builder.build());
    }

    @Test
    void postsTheRequesterAndTextInTheBodyAndMapsRows() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(once(), requestTo(URL))
                .andExpect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer dir-token"))
                .andExpect(jsonPath("$.requesterSubject").value("kc-requester"))
                .andExpect(jsonPath("$.query").value("sevil"))
                .andExpect(jsonPath("$.limit").value(10))
                .andRespond(withSuccess("""
                        {"items":[{"userId":7,"name":"Sevil Kaya","email":"sevil.kaya@acik.com"},
                                  {"userId":null,"name":"bozuk satır"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.searchCandidates("kc-requester", "sevil", 10))
                .containsExactly(new AssigneeCandidate(7L, "Sevil Kaya", "sevil.kaya@acik.com"));
        server.verify();
    }

    @Test
    void requesterNotInDirectoryIsADeny() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"requester_not_in_directory\"}"));

        assertThatThrownBy(() -> client.searchCandidates("kc-ghost", "sevil", 10))
                .isInstanceOf(AssigneeDirectoryClient.DirectoryAccessDeniedException.class);
    }

    @Test
    void anyOtherForbiddenIsOurOutageNotTheUsersDeny() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.searchCandidates("kc-requester", "sevil", 10))
                .isInstanceOf(AssigneeDirectoryClient.ResolutionUnavailableException.class);
    }

    @Test
    void unauthorizedInvalidatesTokenAndRetriesOnce() {
        when(tokens.token()).thenReturn("stale", "fresh");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(URL))
                .andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.searchCandidates("kc-requester", "sevil", 10)).isEmpty();
        verify(tokens).invalidate();
        server.verify();
    }

    @Test
    void directoryOutageFailsClosedAsUnavailable() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.searchCandidates("kc-requester", "sevil", 10))
                .isInstanceOf(AssigneeDirectoryClient.ResolutionUnavailableException.class);
    }

    @Test
    void disabledDirectoryIsUnavailableWithoutACall() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> client.searchCandidates("kc-requester", "sevil", 10))
                .isInstanceOf(AssigneeDirectoryClient.ResolutionUnavailableException.class);
        server.verify();
    }
}
