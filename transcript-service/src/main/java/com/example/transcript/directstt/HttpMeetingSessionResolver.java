package com.example.transcript.directstt;

import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Fail-closed service-token client for meeting-service's canonical resolver. */
@Component
public class HttpMeetingSessionResolver implements MeetingSessionResolver {

    private final DirectSttTranscriptResultConsumerProperties properties;
    private final MeetingSessionServiceTokenProvider tokens;
    private final RestClient restClient;

    public HttpMeetingSessionResolver(
            DirectSttTranscriptResultConsumerProperties properties,
            MeetingSessionServiceTokenProvider tokens,
            RestClient.Builder builder) {
        this.properties = properties;
        this.tokens = tokens;
        this.restClient = builder.clone()
                .requestFactory(requestFactory(properties))
                .build();
    }

    @Override
    public MeetingSessionResolution resolve(
            UUID tenantId, UUID meetingId, String sourceSessionId) {
        if (!properties.getMapping().isEnabled()) {
            return MeetingSessionResolution.failure(
                    MeetingSessionResolution.Status.UNAVAILABLE, "RESOLVER_DISABLED");
        }
        try {
            return call(tenantId, meetingId, sourceSessionId);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                tokens.invalidate();
                try {
                    return call(tenantId, meetingId, sourceSessionId);
                } catch (RestClientException retryFailure) {
                    return classify(retryFailure);
                }
            }
            return classify(ex);
        } catch (RestClientException | IllegalStateException ex) {
            return MeetingSessionResolution.failure(
                    MeetingSessionResolution.Status.UNAVAILABLE, "RESOLVER_UNAVAILABLE");
        }
    }

    private MeetingSessionResolution call(
            UUID tenantId, UUID meetingId, String sourceSessionId) {
        ResolverResponse response = restClient.post()
                .uri(properties.getMapping().getMeetingServiceBaseUrl()
                        + "/api/v1/internal/meetings/{meetingId}/sessions/resolve", meetingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                .body(new ResolverRequest(tenantId, sourceSessionId))
                .retrieve()
                .body(ResolverResponse.class);
        if (response == null) {
            return MeetingSessionResolution.failure(
                    MeetingSessionResolution.Status.INVALID, "EMPTY_RESOLVER_RESPONSE");
        }
        return MeetingSessionResolution.resolved(
                response.tenantId(), response.orgId(), response.meetingId(),
                response.sessionId(), response.externalSessionId());
    }

    private static MeetingSessionResolution classify(RestClientException exception) {
        if (exception instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 404) {
                return MeetingSessionResolution.failure(
                        MeetingSessionResolution.Status.NOT_FOUND, "MAPPING_NOT_FOUND");
            }
            if (status == 400 || status == 409) {
                return MeetingSessionResolution.failure(
                        MeetingSessionResolution.Status.INVALID, "MAPPING_REJECTED");
            }
        }
        return MeetingSessionResolution.failure(
                MeetingSessionResolution.Status.UNAVAILABLE, "RESOLVER_UNAVAILABLE");
    }

    static SimpleClientHttpRequestFactory requestFactory(
            DirectSttTranscriptResultConsumerProperties properties) {
        var cfg = properties.getMapping();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(1, cfg.getConnectTimeoutMillis()));
        factory.setReadTimeout(Math.max(1, cfg.getResponseTimeoutMillis()));
        return factory;
    }

    private record ResolverRequest(UUID tenantId, String externalSessionId) {
    }

    private record ResolverResponse(
            UUID tenantId,
            UUID orgId,
            UUID meetingId,
            UUID sessionId,
            String externalSessionId) {
    }
}
