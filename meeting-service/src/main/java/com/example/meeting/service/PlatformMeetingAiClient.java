package com.example.meeting.service;

import com.example.meeting.config.MeetingAiProperties;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeResponse;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceTranscriptSegment;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Service
public class PlatformMeetingAiClient implements MeetingAiClient {

    private final MeetingAiProperties properties;

    public PlatformMeetingAiClient(MeetingAiProperties properties) {
        this.properties = properties;
    }

    @Override
    public MeetingIntelligenceAnalyzeResponse analyze(
            UUID meetingId,
            String sessionId,
            String transcript,
            List<MeetingIntelligenceTranscriptSegment> segments) {
        URI baseUrl = properties.baseUrl();
        if (!properties.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Meeting AI upstream is not configured.");
        }

        try {
            MeetingIntelligenceAnalyzeResponse response = restClient(baseUrl)
                    .post()
                    .uri(properties.analyzePath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new AnalyzePayload(
                            transcript,
                            meetingId.toString(),
                            sessionId,
                            segments == null ? null : segments.stream().map(AnalyzeSegment::from).toList()))
                    .retrieve()
                    .body(MeetingIntelligenceAnalyzeResponse.class);
            if (response == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meeting AI returned an empty response.");
            }
            return response;
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Meeting AI upstream unavailable.", ex);
        } catch (RestClientResponseException ex) {
            throw mapUpstreamStatus(ex);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meeting AI gateway request failed.", ex);
        }
    }

    private RestClient restClient(URI baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (int) Math.min(properties.requestTimeout().toMillis(), Integer.MAX_VALUE);
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        return RestClient.builder()
                .baseUrl(baseUrl.toString())
                .requestFactory(requestFactory)
                .build();
    }

    private ResponseStatusException mapUpstreamStatus(RestClientResponseException ex) {
        return switch (ex.getStatusCode().value()) {
            case 413 -> new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Meeting AI rejected transcript size.", ex);
            case 422 -> new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Meeting AI rejected transcript source.", ex);
            case 501 -> new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Meeting AI backend is not wired.", ex);
            case 502, 503, 504 -> new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Meeting AI upstream unavailable.", ex);
            default -> new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Meeting AI upstream request failed.", ex);
        };
    }

    private record AnalyzePayload(
            String transcript,
            @JsonProperty("meeting_id") String meetingId,
            @JsonProperty("session_id") String sessionId,
            List<AnalyzeSegment> segments
    ) {
    }

    private record AnalyzeSegment(String text, double start, Double end) {

        static AnalyzeSegment from(MeetingIntelligenceTranscriptSegment segment) {
            double start = segment.start() == null ? 0.0 : segment.start();
            return new AnalyzeSegment(segment.text().trim(), start, segment.end());
        }
    }
}
