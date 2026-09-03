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

@ExtendWith(MockitoExtension.class)
class HttpAssigneeDirectoryClientResolveUserIdTest {

    private static final String URL = "http://user-service:8089/api/users/internal/authenticated-principal/resolve";
    private static final String ISSUER = "https://testai.acik.com/realms/platform-test";

    @Mock private AssigneeDirectoryTokenProvider tokens;
    private MockRestServiceServer server;
    private HttpAssigneeDirectoryClient client;

    @BeforeEach
    void setUp() {
        MeetingAssigneeDirectoryProperties properties = new MeetingAssigneeDirectoryProperties();
        properties.setEnabled(true);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpAssigneeDirectoryClient(properties, tokens, builder.build());
    }

    @Test
    void resolvesTheNumericUserBehindASubject() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(once(), requestTo(URL))
                .andExpect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer dir-token"))
                .andExpect(jsonPath("$.issuer").value(ISSUER))
                .andExpect(jsonPath("$.subject").value("kc-sub-4"))
                .andRespond(withSuccess("{\"userId\":4,\"subjectMatched\":true,\"email\":\"z@acik.com\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveUserId(ISSUER, "kc-sub-4")).contains(4L);
        server.verify();
    }

    @Test
    void unknownSubjectIsEmptyNotAnError() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.resolveUserId(ISSUER, "ghost")).isEmpty();
        server.verify();
    }

    @Test
    void unauthorizedInvalidatesTokenAndRetriesOnce() {
        when(tokens.token()).thenReturn("stale", "fresh");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(URL))
                .andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess("{\"userId\":4,\"subjectMatched\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.resolveUserId(ISSUER, "kc-sub-4")).contains(4L);
        verify(tokens).invalidate();
        server.verify();
    }

    @Test
    void directoryOutageFailsClosedAsUnavailable() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.resolveUserId(ISSUER, "kc-sub-4"))
                .isInstanceOf(AssigneeDirectoryClient.ResolutionUnavailableException.class);
    }

    @Test
    void blankSubjectShortCircuits() {
        assertThat(client.resolveUserId(ISSUER, " ")).isEmpty();
        server.verify();
    }
}
