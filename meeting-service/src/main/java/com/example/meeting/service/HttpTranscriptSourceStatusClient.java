package com.example.meeting.service;

import com.example.common.meeting.events.RecordingOutcome;
import com.example.meeting.config.MeetingTranscriptReadProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Separate mapping from snapshot reads: status 404 is not evidence of retention. */
@Component
public class HttpTranscriptSourceStatusClient implements TranscriptSourceStatusClient {
    private final MeetingTranscriptReadProperties properties;
    private final MeetingTranscriptReadTokenProvider tokens;
    private final RestClient client;

    @Autowired
    public HttpTranscriptSourceStatusClient(MeetingTranscriptReadProperties properties,
            MeetingTranscriptReadTokenProvider tokens, RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMillis());
        factory.setReadTimeout(properties.getResponseTimeoutMillis());
        this.properties = properties;
        this.tokens = tokens;
        this.client = builder.clone().requestFactory(factory).build();
    }

    HttpTranscriptSourceStatusClient(MeetingTranscriptReadProperties properties,
            MeetingTranscriptReadTokenProvider tokens, RestClient client) {
        this.properties = properties;
        this.tokens = tokens;
        this.client = client;
    }

    @Override
    public Observation read(UUID tenantId, UUID meetingId, UUID sessionId) {
        if (!properties.isEnabled()) {
            throw new ReadFailure(Failure.UNAVAILABLE);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return call(tenantId, meetingId, sessionId);
            } catch (ReadFailure bounded) {
                throw bounded;
            } catch (RestClientResponseException failed) {
                if (attempt == 0 && failed.getStatusCode().value() == 401) {
                    tokens.invalidate();
                    continue;
                }
                throw mapped(failed.getStatusCode().value());
            } catch (RestClientException | IllegalStateException unavailable) {
                throw new ReadFailure(Failure.UNAVAILABLE);
            }
        }
        throw new ReadFailure(Failure.UNAVAILABLE);
    }

    private Observation call(UUID tenantId, UUID meetingId, UUID sessionId) {
        var response = client.get().uri(properties.getTranscriptServiceBaseUrl()
                        + "/api/v1/internal/tenants/{tenantId}/meetings/{meetingId}/sessions/{sessionId}/source-status",
                        tenantId, meetingId, sessionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve().toEntity(JsonNode.class);
        if (response.getHeaders().containsKey("X-Analysis-Job-Capability")
                || response.getHeaders().containsKey("X-Analysis-Job-Capability-Expires-At")) {
            throw new ReadFailure(Failure.INVALID_RESPONSE);
        }
        Observation observation = decode(response.getBody());
        if (!tenantId.equals(observation.tenantId()) || !meetingId.equals(observation.meetingId())
                || !sessionId.equals(observation.sessionId())) {
            throw new ReadFailure(Failure.INVALID_RESPONSE);
        }
        return observation;
    }

    private static Observation decode(JsonNode json) {
        try {
            if (json == null || !json.isObject()) {
                throw new IllegalArgumentException();
            }
            State state = State.valueOf(text(json, "state"));
            Long cycle = number(json, "cycleVersion");
            Long revision = number(json, "observationRevision");
            RecordingOutcome outcome = RecordingOutcome.valueOf(text(json, "recordingOutcome"));
            String reason = optionalText(json, "recordingIncompleteReason");
            outcome.validateReason(reason);
            String code = optionalText(json, "failureCode");
            FailureCode failure = code == null ? null : FailureCode.valueOf(code);
            if ((cycle == null) != (revision == null) || (cycle == null && state != State.UNKNOWN)
                    || (state != State.FAILED && failure != null)
                    || (cycle == null && outcome != RecordingOutcome.UNKNOWN)
                    || (outcome == RecordingOutcome.UNKNOWN
                        && state != State.UNKNOWN && state != State.AWAITING_CLOSURE)) {
                throw new IllegalArgumentException();
            }
            Occurrence occurrence = null;
            JsonNode rawOccurrence = json.get("finalizedOccurrence");
            if (rawOccurrence != null && !rawOccurrence.isNull()) {
                if (!rawOccurrence.isObject() || state != State.FINALIZED) {
                    throw new IllegalArgumentException();
                }
                Long version = number(rawOccurrence, "finalizationVersion");
                RecordingOutcome immutableOutcome = RecordingOutcome.valueOf(text(rawOccurrence, "recordingOutcome"));
                String immutableReason = optionalText(rawOccurrence, "recordingIncompleteReason");
                immutableOutcome.validateReason(immutableReason);
                if (version == null || version < 1 || !version.equals(cycle)
                        || (immutableOutcome != RecordingOutcome.UNKNOWN
                            && (immutableOutcome != outcome || !java.util.Objects.equals(immutableReason, reason)))) {
                    throw new IllegalArgumentException();
                }
                occurrence = new Occurrence(version, uuid(rawOccurrence, "analysisRunId"),
                        Instant.parse(text(rawOccurrence, "finalizedAt")), immutableOutcome, immutableReason);
            }
            if ((state == State.FINALIZED) != (occurrence != null)
                    || ((state == State.QUIESCING || state == State.FAILED) && cycle < 1)) {
                throw new IllegalArgumentException();
            }
            return new Observation(uuid(json, "tenantId"), uuid(json, "meetingId"), uuid(json, "sessionId"),
                    state, cycle, revision, Instant.parse(text(json, "observedAt")), failure, outcome, reason, occurrence);
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new ReadFailure(Failure.INVALID_RESPONSE);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(); }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) { return null; }
        if (!value.isTextual()) { throw new IllegalArgumentException(); }
        return value.textValue();
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) { return null; }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException();
        }
        return value.longValue();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equalsIgnoreCase(value)) { throw new IllegalArgumentException(); }
        return parsed;
    }

    private static ReadFailure mapped(int status) {
        return new ReadFailure(switch (status) {
            case 410 -> Failure.ERASED;
            case 423 -> Failure.ERASURE_PENDING;
            case 409 -> Failure.INTEGRITY_CONFLICT;
            default -> Failure.UNAVAILABLE;
        });
    }
}
