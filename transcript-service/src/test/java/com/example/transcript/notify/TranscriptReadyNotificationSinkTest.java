package com.example.transcript.notify;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.example.transcript.events.TranscriptMeetingEventMessage;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TranscriptReadyNotificationSinkTest {
    final UUID meeting = UUID.randomUUID(), org = UUID.randomUUID();
    TranscriptReadyNotificationSink sink;
    MockRestServiceServer server;
    @BeforeEach void setup() {
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        var p = new TranscriptNotifyProperties(); p.setEnabled(true); p.setClientSecret("test-only");
        p.setMeetingBaseUrl("http://meeting"); p.setOrchestratorBaseUrl("http://notify"); p.setTokenUrl("http://auth/token");
        sink = new TranscriptReadyNotificationSink(p, builder.build());
    }
    TranscriptMeetingEventMessage event() { return new TranscriptMeetingEventMessage("occurrence", "meeting.transcript.ready", meeting, meeting, org, org, "{}"); }
    void token(String value) {
        server.expect(requestTo("http://auth/token")).andRespond(withSuccess("{\"access_token\":\"" + value + "\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }
    void recipients(String body) {
        server.expect(requestTo("http://meeting/api/v1/internal/meetings/" + meeting + "/notification-recipients"))
            .andExpect(header("Authorization", "Bearer meeting-token"))
            .andExpect(content().json("{\"tenantId\":\"" + org + "\",\"orgId\":\"" + org + "\"}"))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
    @Test void resolvedRecipientProducesFixedNativeIntentWithoutContent() {
        token("meeting-token"); recipients("[7,7]"); token("notify-token");
        server.expect(requestTo("http://notify/api/v1/internal/notify/intents"))
            .andExpect(header("Authorization", "Bearer notify-token"))
            .andExpect(jsonPath("$.idempotencyKey").value("occurrence|native-ready|7"))
            .andExpect(jsonPath("$.recipients[0].subscriberId").value("7"))
            .andExpect(jsonPath("$.payload.meetingId").value(meeting.toString()))
            .andExpect(jsonPath("$.payload.pushAudience").value("native"))
            .andExpect(jsonPath("$.payload.transcript").doesNotExist())
            .andExpect(jsonPath("$.channels[0]").value("push"))
            .andRespond(withStatus(HttpStatus.ACCEPTED));
        sink.deliver(event()); server.verify();
    }
    @Test void authorizationFailurePropagatesForOutboxRetry() {
        token("meeting-token");
        server.expect(requestTo("http://meeting/api/v1/internal/meetings/" + meeting + "/notification-recipients"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> sink.deliver(event())).isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        server.verify();
    }
    @Test void emptyAudienceDoesNotRequestNotifyToken() {
        token("meeting-token"); recipients("[]"); sink.deliver(event()); server.verify();
    }
    @Test void malformedAudienceIsNotSuccess() {
        token("meeting-token"); recipients("[0]");
        assertThatThrownBy(() -> sink.deliver(event())).isInstanceOf(IllegalStateException.class); server.verify();
    }
    @Test void submissionFailurePropagatesInsteadOfDroppingEvent() {
        token("meeting-token"); recipients("[7]"); token("notify-token");
        server.expect(requestTo("http://notify/api/v1/internal/notify/intents")).andRespond(withServerError());
        assertThatThrownBy(() -> sink.deliver(event())).isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        server.verify();
    }

    @Test void partialRecipientFailureThenRestartReusesAcceptedIntentAndResolvesFreshAudience() {
        var acceptedBody = new java.util.concurrent.atomic.AtomicReference<String>();
        token("meeting-token"); recipients("[7,8]"); token("notify-token");
        server.expect(requestTo("http://notify/api/v1/internal/notify/intents"))
            .andExpect(request -> acceptedBody.set(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
            .andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo("http://notify/api/v1/internal/notify/intents"))
            .andExpect(jsonPath("$.recipients[0].subscriberId").value("8"))
            .andRespond(withServerError());
        assertThatThrownBy(() -> sink.deliver(event())).isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        server.verify();

        setup(); // new process has no token/audience cache; durable event is unchanged
        token("meeting-token"); recipients("[7,8,9]"); token("notify-token");
        server.expect(requestTo("http://notify/api/v1/internal/notify/intents"))
            .andExpect(content().json(acceptedBody.get()))
            .andRespond(withStatus(HttpStatus.ACCEPTED));
        for (long user : new long[] {8, 9}) {
            server.expect(requestTo("http://notify/api/v1/internal/notify/intents"))
                .andExpect(jsonPath("$.idempotencyKey").value("occurrence|native-ready|" + user))
                .andExpect(jsonPath("$.recipients[0].subscriberId").value(Long.toString(user)))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        }
        sink.deliver(event());
        server.verify();
    }
}
