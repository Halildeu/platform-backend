package com.example.meeting.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.meeting.config.MeetingNotifyProperties;
import com.example.meeting.events.MeetingEventMessage;
import com.example.meeting.service.AssigneeDirectoryClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.client.RestClientResponseException;

@ExtendWith(MockitoExtension.class)
class HttpAssignmentNotificationSinkTest {

    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID TENANT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ACTION = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String EVENT_KEY = "meeting.action|" + ACTION + "|meeting.action.reassigned|3";
    private static final String INTENTS_URL = "http://notification-orchestrator:8089/api/v1/internal/notify/intents";

    @Mock private NotifyIntentTokenProvider tokens;
    @Mock private AssigneeDirectoryClient directory;
    private MockRestServiceServer server;
    private HttpAssignmentNotificationSink sink;
    private MeetingNotifyProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MeetingNotifyProperties();
        properties.setEnabled(true);
        properties.setSubjectIssuer("https://testai.acik.com/realms/platform-test");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sink = new HttpAssignmentNotificationSink(
                properties, tokens, directory, builder.build(), new ObjectMapper());
    }

    private static MeetingEventMessage message(String eventType, String payloadJson) {
        return new MeetingEventMessage(
                EVENT_KEY, eventType, "meeting.action", ACTION, 3L, MEETING, TENANT, null, payloadJson);
    }

    @Test
    void handlesOnlyAssignmentEvents() {
        assertThat(sink.handles("meeting.action.assigned")).isTrue();
        assertThat(sink.handles("meeting.action.reassigned")).isTrue();
        assertThat(sink.handles("meeting.transcript.ready")).isFalse();
    }

    @Test
    void deliversAFixedCopyIntentToTheResolvedAssignee() {
        when(tokens.token()).thenReturn("notify-token");
        when(directory.resolveUserId(eq(properties.getSubjectIssuer()), eq("kc-sub-4")))
                .thenReturn(Optional.of(4L));
        server.expect(once(), requestTo(INTENTS_URL))
                .andExpect(request -> assertThat(request.getMethod()).isEqualTo(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer notify-token"))
                .andExpect(jsonPath("$.idempotencyKey").value(EVENT_KEY))
                .andExpect(jsonPath("$.orgId").value(TENANT.toString()))
                .andExpect(jsonPath("$.topicKey").value("meeting.action.reassigned"))
                .andExpect(jsonPath("$.template.templateId").value("meeting.action.reassigned"))
                .andExpect(jsonPath("$.template.version").value(1))
                .andExpect(jsonPath("$.recipients[0].type").value("subscriber"))
                .andExpect(jsonPath("$.recipients[0].subscriberId").value("4"))
                .andExpect(jsonPath("$.channels[0]").value("in-app"))
                .andExpect(jsonPath("$.dataClassification").value("transactional"))
                .andExpect(jsonPath("$.payload").isEmpty())
                // No assignee identity, action text or meeting title leaves through this channel.
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("kc-sub-4"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Bütçe"))))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"intentId\":\"x\",\"status\":\"ACCEPTED\"}"));

        sink.deliver(message("meeting.action.reassigned",
                "{\"schema\":\"meeting.event.v1\",\"assigneeSubject\":\"kc-sub-4\",\"previousAssigneeSubject\":\"kc-sub-1\",\"actionText\":\"Bütçe onayı\"}"));

        server.verify();
    }

    @Test
    void intentIdIsDeterministicAndOrchestratorSafe() {
        String id = HttpAssignmentNotificationSink.intentId(EVENT_KEY);
        assertThat(id).isEqualTo(HttpAssignmentNotificationSink.intentId(EVENT_KEY));
        assertThat(id).matches("^[a-zA-Z0-9_-]+$").hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void unauthorizedInvalidatesTheTokenAndRetriesOnce() {
        when(tokens.token()).thenReturn("stale", "fresh");
        when(directory.resolveUserId(anyString(), eq("kc-sub-4"))).thenReturn(Optional.of(4L));
        server.expect(once(), requestTo(INTENTS_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(once(), requestTo(INTENTS_URL))
                .andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        sink.deliver(message("meeting.action.assigned", "{\"assigneeSubject\":\"kc-sub-4\"}"));

        verify(tokens).invalidate();
        server.verify();
    }

    @Test
    void serverFailurePropagatesSoTheOutboxRowIsRetried() {
        when(tokens.token()).thenReturn("notify-token");
        when(directory.resolveUserId(anyString(), eq("kc-sub-4"))).thenReturn(Optional.of(4L));
        server.expect(once(), requestTo(INTENTS_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> sink.deliver(message("meeting.action.assigned", "{\"assigneeSubject\":\"kc-sub-4\"}")))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @Test
    void unknownAssigneeIsSkippedWithoutCallingTheOrchestrator() {
        when(directory.resolveUserId(anyString(), eq("ghost"))).thenReturn(Optional.empty());

        sink.deliver(message("meeting.action.assigned", "{\"assigneeSubject\":\"ghost\"}"));

        server.verify(); // no expectations registered: any request would have failed
    }

    @Test
    void clearedAssignmentNotifiesNobody() {
        sink.deliver(message("meeting.action.reassigned", "{\"assigneeSubject\":null,\"previousAssigneeSubject\":\"kc-sub-1\"}"));
        server.verify();
    }

    @Test
    void nonAssignmentEventsAreIgnored() {
        sink.deliver(message("meeting.transcript.ready", "{}"));
        server.verify();
    }

    @Test
    void intentBodyFallsBackToTenantWhenOrgIsAbsent() {
        Map<String, Object> body = sink.intent(message("meeting.action.assigned", "{}"), 9L);
        assertThat(body).containsEntry("orgId", TENANT.toString())
                .containsEntry("idempotencyKey", EVENT_KEY)
                .containsEntry("correlationId", MEETING.toString());
    }
}
