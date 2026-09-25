package com.example.meeting.service;

import com.example.meeting.config.MeetingAssigneeDirectoryProperties;
import com.example.meeting.config.TeamsCalendarBridgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class HttpTeamsCalendarTransport implements TeamsCalendarTransport {
    private final TeamsCalendarBridgeProperties properties;
    private final MeetingAssigneeDirectoryProperties directory;
    private final AssigneeDirectoryTokenProvider tokens;
    private final RestClient client;
    private final ObjectMapper mapper;

    @Autowired
    public HttpTeamsCalendarTransport(TeamsCalendarBridgeProperties properties, MeetingAssigneeDirectoryProperties directory,
            RestClient.Builder builder, ObjectMapper mapper) {
        // Reuse the established exact-scope token implementation with a separate bounded transport/cache.
        this(properties, directory, new AssigneeDirectoryTokenProvider(directory, boundedClient(builder), Clock.systemUTC()),
                boundedClient(builder), mapper);
    }

    HttpTeamsCalendarTransport(TeamsCalendarBridgeProperties properties, MeetingAssigneeDirectoryProperties directory,
            AssigneeDirectoryTokenProvider tokens, RestClient client, ObjectMapper mapper) {
        this.properties = properties; this.directory = directory; this.tokens = tokens; this.client = client; this.mapper = mapper;
    }

    private static RestClient boundedClient(RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(2_000); factory.setReadTimeout(25_000);
        return builder.clone().requestFactory(factory).build();
    }

    @Override public Organizer resolve(String issuer, String subject) {
        if (!directory.isEnabled()) throw unavailable();
        String token;
        try { token = tokens.token(); } catch (RuntimeException unavailable) { throw unavailable(); }
        return exchange(HttpMethod.POST, directory.getUserServiceBaseUrl() + "/api/users/internal/microsoft-organizer/resolve",
                HttpHeaders.AUTHORIZATION, "Bearer " + token, Map.of("issuer", issuer, "subject", subject), Organizer.class, true);
    }

    @Override public Choices browse(UUID organizer, OffsetDateTime from, OffsetDateTime to) {
        return worker(HttpMethod.POST, "/api/teams/calendar/events", Map.of("organizerId", organizer, "from", from, "to", to), Choices.class);
    }
    @Override public Schedule select(UUID organizer, UUID meeting, String eventId) {
        // Stable correlation allows a client to read/retry the same selection after an uncertain response.
        return worker(HttpMethod.POST, "/api/teams/meetings/" + meeting + "/calendar-schedule",
                Map.of("organizerId", organizer, "eventId", eventId, "correlationId", "teams-calendar-" + meeting), Schedule.class);
    }
    @Override public Schedule status(UUID organizer, UUID meeting) {
        return worker(HttpMethod.GET, ownedPath(organizer, meeting), null, Schedule.class);
    }
    @Override public void cancel(UUID organizer, UUID meeting) {
        worker(HttpMethod.DELETE, ownedPath(organizer, meeting), null, Void.class);
    }
    private static String ownedPath(UUID organizer, UUID meeting) {
        return "/api/teams/organizers/" + organizer + "/meetings/" + meeting + "/calendar-schedule";
    }
    private <T> T worker(HttpMethod method, String path, Object body, Class<T> type) {
        if (!properties.isConfigured()) throw unavailable();
        return exchange(method, properties.getWorkerBaseUrl() + path, "X-Teams-Control-Key", properties.getControlKey(), body, type, false);
    }
    private <T> T exchange(HttpMethod method, String uri, String header, String credential, Object body, Class<T> type, boolean identity) {
        try {
            var request = client.method(method).uri(uri).header(header, credential).accept(MediaType.APPLICATION_JSON);
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
            return request.exchange((sent, received) -> {
                int status = received.getStatusCode().value();
                if (identity ? status != 200 : (type == Void.class ? status != 204 : status != 200 && status != 202)) {
                    // Never reflect upstream representations, locations, credentials or detailed errors.
                    if (status == 403) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "teams_calendar_not_allowed");
                    if (!identity && status == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "teams_schedule_not_found");
                    if (!identity && status == 409) throw new ResponseStatusException(HttpStatus.CONFLICT, "teams_schedule_conflict");
                    throw unavailable();
                }
                if (type == Void.class) return null;
                byte[] bytes = received.getBody().readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024 || bytes.length == 0) throw unavailable();
                return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            });
        } catch (RestClientException invalid) { throw unavailable(); }
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "teams_calendar_unavailable");
    }
}
