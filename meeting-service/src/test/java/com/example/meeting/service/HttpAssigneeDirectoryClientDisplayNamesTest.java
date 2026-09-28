package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.meeting.config.MeetingAssigneeDirectoryProperties;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** gitops#3834 — batch display-name lookup behind the task list's assignee column. */
@ExtendWith(MockitoExtension.class)
class HttpAssigneeDirectoryClientDisplayNamesTest {

    private static final String URL = "http://user-service:8089/api/users/internal/display-names";

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
    void mapsKnownSubjectsAndLeavesUnknownOrErasedOut() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(once(), requestTo(URL))
                .andExpect(header("Authorization", "Bearer dir-token"))
                .andExpect(jsonPath("$.subjects[0]").value("kc-7"))
                .andExpect(jsonPath("$.subjects[1]").value("kc-ghost"))
                .andRespond(withSuccess("""
                        [{"subject":"kc-7","displayName":"Sevil Kaya"},
                         {"subject":"kc-ghost","displayName":null}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.resolveDisplayNames(List.of("kc-7", "kc-ghost")))
                .containsExactlyEntriesOf(java.util.Map.of("kc-7", "Sevil Kaya"));
        server.verify();
    }

    @Test
    void splitsLargeListsIntoTheDirectoryBatchSize() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(twice(), requestTo(URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<String> subjects = IntStream.range(0, HttpAssigneeDirectoryClient.DISPLAY_NAME_BATCH + 1)
                .mapToObj(i -> "kc-" + i).toList();
        assertThat(client.resolveDisplayNames(subjects)).isEmpty();
        server.verify();
    }

    @Test
    void unauthorizedInvalidatesTokenAndRetriesOnce() {
        when(tokens.token()).thenReturn("stale", "fresh");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(URL))
                .andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess("[{\"subject\":\"kc-7\",\"displayName\":\"Sevil Kaya\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.resolveDisplayNames(List.of("kc-7"))).containsEntry("kc-7", "Sevil Kaya");
        verify(tokens).invalidate();
        server.verify();
    }

    @Test
    void directoryOutageIsUnavailable() {
        when(tokens.token()).thenReturn("dir-token");
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.resolveDisplayNames(List.of("kc-7")))
                .isInstanceOf(AssigneeDirectoryClient.ResolutionUnavailableException.class);
    }
}
