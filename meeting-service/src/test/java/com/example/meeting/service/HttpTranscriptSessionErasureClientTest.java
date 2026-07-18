package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.meeting.config.MeetingSessionErasureProperties;
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
class HttpTranscriptSessionErasureClientTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String URL = "http://transcript-service:8098/api/v1/internal/tenants/"
            + TENANT + "/meetings/" + MEETING + "/sessions/" + SESSION + "/erasure";

    @Mock private MeetingServiceTokenProvider tokens;
    private MockRestServiceServer server;
    private HttpTranscriptSessionErasureClient client;

    @BeforeEach
    void setUp() {
        MeetingSessionErasureProperties properties = new MeetingSessionErasureProperties();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpTranscriptSessionErasureClient(properties, tokens, builder.build());
        when(tokens.token()).thenReturn("erasure-token");
    }

    @Test
    void completeResponseMustMatchTheRequestedScope() {
        server.expect(once(), requestTo(URL))
                .andExpect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer erasure-token"))
                .andRespond(withSuccess(completeJson(MEETING), MediaType.APPLICATION_JSON));

        var result = client.erase(TENANT, MEETING, SESSION, "source-session");

        assertThat(result.status()).isEqualTo(
                TranscriptSessionErasureClient.Result.Status.COMPLETE);
        assertThat(result.deletedCount()).isEqualTo(3);
        server.verify();
    }

    @Test
    void lockedRemoteScopeMapsToLegalHold() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.LOCKED));

        assertThat(client.erase(TENANT, MEETING, SESSION, "source-session").status())
                .isEqualTo(TranscriptSessionErasureClient.Result.Status.HELD);
        server.verify();
    }

    @Test
    void unauthorizedResponseInvalidatesTokenAndRetriesOnce() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(completeJson(MEETING), MediaType.APPLICATION_JSON));

        assertThat(client.erase(TENANT, MEETING, SESSION, "source-session").deletedCount())
                .isEqualTo(3);

        verify(tokens).invalidate();
        server.verify();
    }

    @Test
    void mismatchedRemoteScopeFailsClosed() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(
                        completeJson(UUID.fromString("44444444-4444-4444-8444-444444444444")),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.erase(TENANT, MEETING, SESSION, "source-session"))
                .isInstanceOfSatisfying(
                        HttpTranscriptSessionErasureClient.RemoteErasureException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo("REMOTE_SCOPE_MISMATCH"));
        server.verify();
    }

    private static String completeJson(UUID meetingId) {
        return """
                {
                  "tenantId":"%s",
                  "meetingId":"%s",
                  "sessionId":"%s",
                  "status":"COMPLETE",
                  "deletedCount":3
                }
                """.formatted(TENANT, meetingId, SESSION);
    }
}
