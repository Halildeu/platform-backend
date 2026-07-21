package com.example.meeting.service;

import com.example.meeting.config.MeetingSessionErasureProperties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Fail-closed service-token client for transcript-service erasure. */
@Component
public class HttpTranscriptSessionErasureClient implements TranscriptSessionErasureClient {

    private final MeetingSessionErasureProperties properties;
    private final MeetingServiceTokenProvider tokens;
    private final RestClient restClient;

    @Autowired
    public HttpTranscriptSessionErasureClient(
            MeetingSessionErasureProperties properties,
            MeetingServiceTokenProvider tokens,
            RestClient.Builder builder) {
        this(properties, tokens, buildClient(properties, builder));
    }

    HttpTranscriptSessionErasureClient(
            MeetingSessionErasureProperties properties,
            MeetingServiceTokenProvider tokens,
            RestClient restClient) {
        this.properties = properties;
        this.tokens = tokens;
        this.restClient = restClient;
    }

    private static RestClient buildClient(
            MeetingSessionErasureProperties properties,
            RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMillis());
        requestFactory.setReadTimeout(properties.getResponseTimeoutMillis());
        return builder.clone().requestFactory(requestFactory).build();
    }

    @Override
    public Result prepare(UUID tenantId, UUID meetingId, UUID sessionId, String sourceSessionId) {
        return invoke(tenantId, meetingId, sessionId, sourceSessionId, true);
    }

    @Override
    public Result erase(UUID tenantId, UUID meetingId, UUID sessionId, String sourceSessionId) {
        return invoke(tenantId, meetingId, sessionId, sourceSessionId, false);
    }

    private Result invoke(
            UUID tenantId, UUID meetingId, UUID sessionId, String sourceSessionId,
            boolean prepare) {
        try {
            return call(tenantId, meetingId, sessionId, sourceSessionId, prepare);
        } catch (RemoteErasureException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                tokens.invalidate();
                try {
                    return call(tenantId, meetingId, sessionId, sourceSessionId, prepare);
                } catch (RemoteErasureException retryFailure) {
                    throw retryFailure;
                } catch (RestClientException | IllegalStateException retryFailure) {
                    throw new RemoteErasureException("REMOTE_AUTH_RETRY_FAILED", retryFailure);
                }
            }
            if (ex.getStatusCode().value() == HttpStatus.LOCKED.value()) {
                return new Result(Result.Status.HELD, 0);
            }
            throw new RemoteErasureException("REMOTE_HTTP_ERROR", ex);
        } catch (RestClientException | IllegalStateException ex) {
            throw new RemoteErasureException("REMOTE_UNAVAILABLE", ex);
        }
    }

    private Result call(
            UUID tenantId, UUID meetingId, UUID sessionId, String sourceSessionId,
            boolean prepare) {
        ErasureResponse response = restClient.post()
                .uri(properties.getTranscriptServiceBaseUrl()
                                + "/api/v1/internal/tenants/{tenantId}/meetings/{meetingId}"
                                + "/sessions/{sessionId}/erasure"
                                + (prepare ? "/prepare" : ""),
                        tenantId, meetingId, sessionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                .body(new ErasureRequest(sourceSessionId))
                .retrieve()
                .body(ErasureResponse.class);
        if (response == null || response.status() == null) {
            throw new RemoteErasureException("REMOTE_INVALID_RESPONSE");
        }
        if (response.status() == Result.Status.HELD) {
            return new Result(Result.Status.HELD, 0);
        }
        boolean acceptedStatus = response.status() == Result.Status.COMPLETE
                || (prepare && response.status() == Result.Status.READY);
        if (!acceptedStatus || !tenantId.equals(response.tenantId())
                || !meetingId.equals(response.meetingId())
                || !sessionId.equals(response.sessionId())) {
            throw new RemoteErasureException("REMOTE_SCOPE_MISMATCH");
        }
        return new Result(response.status(), Math.max(0, response.deletedCount()));
    }

    record ErasureRequest(String sourceSessionId) {
    }

    record ErasureResponse(
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            Result.Status status,
            int deletedCount) {
    }

    public static class RemoteErasureException extends IllegalStateException {
        private final String errorCode;

        RemoteErasureException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }

        RemoteErasureException(String errorCode, Throwable cause) {
            super(errorCode, cause);
            this.errorCode = errorCode;
        }

        public String errorCode() { return errorCode; }
    }
}
