package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.meeting.config.MeetingTranscriptReadProperties;
import java.util.UUID;
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
class HttpCanonicalTranscriptClientTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String URL = "http://transcript-service:8098/api/v1/internal/tenants/"
            + TENANT + "/meetings/" + MEETING + "/sessions/" + SESSION + "/finalizations/7";

    @Mock private MeetingTranscriptReadTokenProvider tokens;
    private MockRestServiceServer server;
    private HttpCanonicalTranscriptClient client;

    @BeforeEach
    void setUp() {
        MeetingTranscriptReadProperties properties = new MeetingTranscriptReadProperties();
        properties.setEnabled(true);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpCanonicalTranscriptClient(properties, tokens, builder.build());
        when(tokens.token()).thenReturn("read-token");
    }

    @Test
    void readUsesCanonicalReadTokenAndReturnsHeaderFreeSnapshot() {
        server.expect(once(), requestTo(URL))
                .andExpect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer read-token"))
                .andExpect(header("X-Tenant-Id", TENANT.toString()))
                .andRespond(withSuccess(json(), MediaType.APPLICATION_JSON));

        var snapshot = client.read(TENANT, MEETING, SESSION, 7L);

        assertThat(snapshot.meetingId()).isEqualTo(MEETING);
        assertThat(snapshot.transcript()).isEqualTo("canonical text");
        server.verify();
    }

    @Test
    void snapshotResponseCarryingCapabilityHeadersFailsClosed() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(json(), MediaType.APPLICATION_JSON)
                        .header("X-Analysis-Job-Capability", "must-not-be-here"));

        assertFailure(CanonicalTranscriptClient.Failure.INVALID_RESPONSE);
        server.verify();
    }

    @Test
    void missingPersistedOccurrenceMapsToRetentionWithoutReadingErrorBody() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"FINALIZATION_NOT_FOUND\"}"));

        assertFailure(CanonicalTranscriptClient.Failure.RETENTION_EXPIRED);
        server.verify();
    }

    private void assertFailure(CanonicalTranscriptClient.Failure failure) {
        assertThatThrownBy(() -> client.read(TENANT, MEETING, SESSION, 7L))
                .isInstanceOfSatisfying(CanonicalTranscriptClient.ReadFailure.class,
                        ex -> assertThat(ex.failure()).isEqualTo(failure));
    }

    private static String json() {
        return """
                {
                  "tenantId":"11111111-1111-4111-8111-111111111111",
                  "meetingId":"22222222-2222-4222-8222-222222222222",
                  "sessionId":"33333333-3333-4333-8333-333333333333",
                  "finalizationVersion":7,
                  "finalizedAt":"2026-07-18T12:00:00Z",
                  "state":"FINALIZED",
                  "transcript":"canonical text",
                  "transcriptSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "segmentCount":1,
                  "segments":[{"text":"canonical text","start":0.0,"end":1.0}]
                }
                """;
    }
}
