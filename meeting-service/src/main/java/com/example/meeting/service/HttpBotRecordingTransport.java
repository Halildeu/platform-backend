package com.example.meeting.service;

import static com.example.common.meeting.bot.BotRecordingContract.*;
import com.example.meeting.config.BotRecordingProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, no-redirect service transport. A timeout is an unknown outcome: caller retries the SAME request key. */
@Component
public class HttpBotRecordingTransport implements BotRecordingTransport {
    private final BotRecordingProperties properties;
    private final RestClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public HttpBotRecordingTransport(BotRecordingProperties properties, RestClient.Builder builder, ObjectMapper mapper) {
        this(properties, boundedClient(builder), mapper, Clock.systemUTC());
    }
    HttpBotRecordingTransport(BotRecordingProperties properties, RestClient client, ObjectMapper mapper, Clock clock) {
        this.properties = properties; this.client = client; this.mapper = mapper; this.clock = clock;
    }
    static RestClient boundedClient(RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method); connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(2000); factory.setReadTimeout(10000);
        return builder.clone().requestFactory(factory).build();
    }
    @Override public Snapshot grant(Grant value) { return command("grant", value); }
    @Override public Snapshot inspect(IntentRef value) { return command("inspect", value); }
    @Override public Snapshot findRequest(RequestRef value) { return command("find-request", value); }
    @Override public Snapshot bind(Bind value) { return command("bind", value); }
    @Override public Snapshot revoke(Lookup value) { return command("revoke", value); }

    private Snapshot command(String operation, Object body) {
        if (!properties.transportConfigured()) throw unavailable();
        String token = token();
        try {
            return client.post().uri(properties.getOwnerBaseUrl() + "/api/v1/internal/bot-recording/" + operation)
                    .headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(body).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "bot_recording_not_found");
                        if (status == 409) throw new ResponseStatusException(HttpStatus.CONFLICT, "bot_recording_conflict");
                        if (status == 401 || status == 403) invalidate();
                        if (status != 200) throw unavailable();
                        return read(response.getBody().readNBytes(65537), Snapshot.class);
                    });
        } catch (ResponseStatusException failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
    }
    private synchronized String token() {
        Instant now = clock.instant();
        if (cachedToken != null && expiresAt.isAfter(now.plusSeconds(5))) return cachedToken;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials"); form.add("audience", "audit-event-consumer-service");
        form.add("permissions", "audit:bot-recording:manage");
        try {
            Token minted = client.post().uri(properties.getTokenUrl())
                    .headers(h -> h.setBasicAuth("meeting-service", properties.getClientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) throw unavailable();
                        return read(response.getBody().readNBytes(65537), Token.class);
                    });
            if (minted == null || minted.accessToken() == null || minted.accessToken().isBlank()
                    || minted.accessToken().length() > 16384 || minted.accessToken().chars().anyMatch(Character::isWhitespace)
                    || minted.accessToken().chars().anyMatch(Character::isISOControl)
                    || !"Bearer".equalsIgnoreCase(minted.tokenType()) || minted.expiresIn() <= 5 || minted.expiresIn() > 3600)
                throw unavailable();
            cachedToken = minted.accessToken(); expiresAt = now.plusSeconds(minted.expiresIn());
            return cachedToken;
        } catch (RuntimeException failure) { throw unavailable(); }
    }
    private <T> T read(byte[] bytes, Class<T> type) {
        if (bytes.length == 0 || bytes.length > 65536) throw unavailable();
        try { return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes); }
        catch (IOException invalid) { throw unavailable(); }
    }
    private synchronized void invalidate() { cachedToken = null; expiresAt = Instant.EPOCH; }
    record Token(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn,
                 @JsonProperty("token_type") String tokenType) {}
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "bot_recording_owner_unavailable");
    }
}
