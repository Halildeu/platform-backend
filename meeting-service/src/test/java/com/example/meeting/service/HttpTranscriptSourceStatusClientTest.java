package com.example.meeting.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.example.meeting.config.MeetingTranscriptReadProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpTranscriptSourceStatusClientTest {
    private static final UUID TENANT = UUID.randomUUID(), MEETING = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static final String URL = "http://transcript-service:8098/api/v1/internal/tenants/"
            + TENANT + "/meetings/" + MEETING + "/sessions/" + SESSION + "/source-status";
    private final MeetingTranscriptReadTokenProvider tokens = mock(MeetingTranscriptReadTokenProvider.class);
    private final MeetingTranscriptReadProperties properties = new MeetingTranscriptReadProperties();
    private MockRestServiceServer server;
    private HttpTranscriptSourceStatusClient client;

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpTranscriptSourceStatusClient(properties, tokens, builder.build());
        when(tokens.token()).thenReturn("read-token");
    }

    @Test
    void scopedReadDoesNotIssueJobCapabilitiesOrClaimWorkerActivity() {
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer read-token"))
                .andExpect(header("X-Tenant-Id", TENANT.toString()))
                .andExpect(request -> {
                    assertThat(request.getHeaders()).doesNotContainKeys("X-Analysis-Run-Id", "X-Analysis-Spec-Version");
                }).andRespond(withSuccess(body().toString(), MediaType.APPLICATION_JSON));
        assertThat(client.read(TENANT, MEETING, SESSION).state()).isEqualTo(TranscriptSourceStatusClient.State.QUIESCING);
        server.verify();
    }

    @Test
    void historicalOccurrenceCanHaveUnknownClosureAfterAssociationBackfill() {
        var json = finalized();
        ((ObjectNode) json.get("finalizedOccurrence")).put("recordingOutcome", "UNKNOWN");
        respond(json);
        assertThat(client.read(TENANT, MEETING, SESSION).finalizedOccurrence().recordingOutcome().name()).isEqualTo("UNKNOWN");
    }

    @Test
    void authoritativeUnknownRemainsDistinctFromUnavailableEndpoint() {
        var json = body().put("state", "UNKNOWN").put("recordingOutcome", "UNKNOWN")
                .putNull("cycleVersion").putNull("observationRevision");
        respond(json);
        assertThat(client.read(TENANT, MEETING, SESSION).state()).isEqualTo(TranscriptSourceStatusClient.State.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({"404,UNAVAILABLE", "503,UNAVAILABLE", "403,UNAVAILABLE", "410,ERASED", "423,ERASURE_PENDING", "409,INTEGRITY_CONFLICT"})
    void errorMappingDoesNotConfuseAbsenceWithRetention(int status, TranscriptSourceStatusClient.Failure failure) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)).body("private internal details"));
        fails(failure);
    }

    @Test
    void oneAuthenticationRetryUsesRefreshedCredential() {
        when(tokens.token()).thenReturn("expired", "fresh");
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer expired"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess(body().toString(), MediaType.APPLICATION_JSON));
        client.read(TENANT, MEETING, SESSION);
        verify(tokens).invalidate();
        server.verify();
    }

    @Test
    void repeatedUnauthorizedStopsAfterOneRetry() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        fails(TranscriptSourceStatusClient.Failure.UNAVAILABLE);
        verify(tokens, times(2)).token();
    }

    @Test
    void disabledReadDoesNotContactUpstream() {
        properties.setEnabled(false);
        fails(TranscriptSourceStatusClient.Failure.UNAVAILABLE);
        verifyNoInteractions(tokens);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenantId", "meetingId", "sessionId"})
    void differentScopeIsRejected(String field) {
        respond(body().put(field, UUID.randomUUID().toString()));
        fails(TranscriptSourceStatusClient.Failure.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Analysis-Job-Capability", "X-Analysis-Job-Capability-Expires-At"})
    void capabilityResponseIsRejected(String header) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body().toString(), MediaType.APPLICATION_JSON).header(header, "unexpected"));
        fails(TranscriptSourceStatusClient.Failure.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordinalState", "ordinalOutcome", "negativeCycle", "fractionalCycle", "missingCycle",
            "missingRevision", "missingTime", "unknownClosure", "badReason", "wrongFailureCode", "spuriousFailure",
            "noFinalizedOccurrence", "wrongVersion", "missingRun", "badUuid", "ordinalFailure", "contradictoryClosure"})
    void malformedContractIsRejected(String mutation) {
        ObjectNode json = body();
        switch (mutation) {
            case "ordinalState" -> json.put("state", 2);
            case "ordinalOutcome" -> json.put("recordingOutcome", 1);
            case "negativeCycle" -> json.put("cycleVersion", -1);
            case "fractionalCycle" -> json.put("cycleVersion", 1.5);
            case "missingCycle" -> json.remove("cycleVersion");
            case "missingRevision" -> json.remove("observationRevision");
            case "missingTime" -> json.remove("observedAt");
            case "unknownClosure" -> json.put("recordingOutcome", "UNKNOWN");
            case "badReason" -> json.put("recordingIncompleteReason", "private arbitrary message");
            case "wrongFailureCode" -> json.put("state", "FAILED").put("failureCode", "MODEL_RUNNING");
            case "spuriousFailure" -> json.put("failureCode", "INVALID_CANONICAL_SEGMENT");
            case "ordinalFailure" -> json.put("failureCode", 1);
            case "noFinalizedOccurrence" -> json.put("state", "FINALIZED");
            case "wrongVersion" -> { json = finalized(); ((ObjectNode) json.get("finalizedOccurrence")).put("finalizationVersion", 3); }
            case "missingRun" -> { json = finalized(); ((ObjectNode) json.get("finalizedOccurrence")).remove("analysisRunId"); }
            case "badUuid" -> json.put("sessionId", "1-1-1-1-1");
            case "contradictoryClosure" -> { json = finalized(); ((ObjectNode) json.get("finalizedOccurrence"))
                    .put("recordingOutcome", "INCOMPLETE").put("recordingIncompleteReason", "CLOSURE_UNCONFIRMED"); }
            default -> throw new AssertionError(mutation);
        }
        respond(json);
        fails(TranscriptSourceStatusClient.Failure.INVALID_RESPONSE);
    }

    private void respond(ObjectNode json) {
        server.expect(requestTo(URL)).andRespond(withSuccess(json.toString(), MediaType.APPLICATION_JSON));
    }

    private void fails(TranscriptSourceStatusClient.Failure failure) {
        assertThatThrownBy(() -> client.read(TENANT, MEETING, SESSION))
                .isInstanceOfSatisfying(TranscriptSourceStatusClient.ReadFailure.class, error -> {
                    assertThat(error.failure()).isEqualTo(failure);
                    assertThat(error.getMessage()).isEqualTo(failure.name());
                });
        server.verify();
    }

    private static ObjectNode finalized() {
        var json = body().put("state", "FINALIZED");
        json.putObject("finalizedOccurrence").put("finalizationVersion", 2)
                .put("analysisRunId", UUID.randomUUID().toString()).put("finalizedAt", "2026-09-26T18:54:59Z")
                .put("recordingOutcome", "FINISHED").putNull("recordingIncompleteReason");
        return json;
    }

    private static ObjectNode body() {
        return new ObjectMapper().createObjectNode().put("tenantId", TENANT.toString())
                .put("meetingId", MEETING.toString()).put("sessionId", SESSION.toString())
                .put("state", "QUIESCING").put("cycleVersion", 2).put("observationRevision", 8)
                .put("observedAt", "2026-09-26T18:55:00Z").putNull("failureCode")
                .put("recordingOutcome", "FINISHED").putNull("recordingIncompleteReason").putNull("finalizedOccurrence");
    }
}
