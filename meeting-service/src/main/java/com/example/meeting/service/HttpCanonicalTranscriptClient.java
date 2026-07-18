package com.example.meeting.service;

import com.example.meeting.config.MeetingTranscriptReadProperties;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Header-hardened, service-token client for one exact canonical occurrence. */
@Component
public class HttpCanonicalTranscriptClient implements CanonicalTranscriptClient {

    private static final String CAPABILITY_HEADER = "X-Analysis-Job-Capability";
    private static final String CAPABILITY_EXPIRY_HEADER =
            "X-Analysis-Job-Capability-Expires-At";

    private final MeetingTranscriptReadProperties properties;
    private final MeetingTranscriptReadTokenProvider tokens;
    private final RestClient restClient;

    public HttpCanonicalTranscriptClient(
            MeetingTranscriptReadProperties properties,
            MeetingTranscriptReadTokenProvider tokens,
            RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMillis());
        requestFactory.setReadTimeout(properties.getResponseTimeoutMillis());
        this.properties = properties;
        this.tokens = tokens;
        this.restClient = builder.clone().requestFactory(requestFactory).build();
    }

    HttpCanonicalTranscriptClient(
            MeetingTranscriptReadProperties properties,
            MeetingTranscriptReadTokenProvider tokens,
            RestClient restClient) {
        this.properties = properties;
        this.tokens = tokens;
        this.restClient = restClient;
    }

    @Override
    public Snapshot read(
            UUID tenantId, UUID meetingId, UUID sessionId, long finalizationVersion) {
        if (!properties.isEnabled()) {
            throw new ReadFailure(Failure.UNAVAILABLE);
        }
        try {
            return call(tenantId, meetingId, sessionId, finalizationVersion);
        } catch (ReadFailure ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                tokens.invalidate();
                try {
                    return call(tenantId, meetingId, sessionId, finalizationVersion);
                } catch (ReadFailure retryFailure) {
                    throw retryFailure;
                } catch (RestClientResponseException retryFailure) {
                    throw mapped(retryFailure.getStatusCode().value());
                } catch (RestClientException | IllegalStateException retryFailure) {
                    throw new ReadFailure(Failure.UNAVAILABLE);
                }
            }
            throw mapped(ex.getStatusCode().value());
        } catch (RestClientException | IllegalStateException ex) {
            throw new ReadFailure(Failure.UNAVAILABLE);
        }
    }

    private Snapshot call(
            UUID tenantId, UUID meetingId, UUID sessionId, long finalizationVersion) {
        ResponseEntity<Snapshot> response = restClient.get()
                .uri(properties.getTranscriptServiceBaseUrl()
                                + "/api/v1/internal/tenants/{tenantId}/meetings/{meetingId}"
                                + "/sessions/{sessionId}/finalizations/{finalizationVersion}",
                        tenantId, meetingId, sessionId, finalizationVersion)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .toEntity(Snapshot.class);
        if (response.getHeaders().getFirst(CAPABILITY_HEADER) != null
                || response.getHeaders().getFirst(CAPABILITY_EXPIRY_HEADER) != null) {
            throw new ReadFailure(Failure.INVALID_RESPONSE);
        }
        Snapshot snapshot = response.getBody();
        if (snapshot == null || snapshot.segments() == null) {
            throw new ReadFailure(Failure.INVALID_RESPONSE);
        }
        return snapshot;
    }

    private static ReadFailure mapped(int status) {
        if (status == HttpStatus.NOT_FOUND.value()) {
            return new ReadFailure(Failure.RETENTION_EXPIRED);
        }
        if (status == HttpStatus.GONE.value()) {
            return new ReadFailure(Failure.ERASED);
        }
        if (status == HttpStatus.LOCKED.value()) {
            return new ReadFailure(Failure.ERASURE_PENDING);
        }
        if (status == HttpStatus.CONFLICT.value()) {
            return new ReadFailure(Failure.INTEGRITY_CONFLICT);
        }
        return new ReadFailure(Failure.UNAVAILABLE);
    }
}
