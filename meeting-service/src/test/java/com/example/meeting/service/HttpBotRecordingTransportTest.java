package com.example.meeting.service;

import static com.example.common.meeting.bot.BotRecordingContract.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.example.meeting.config.BotRecordingProperties;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class HttpBotRecordingTransportTest {
    private final BotRecordingProperties properties = new BotRecordingProperties();
    private final Instant now = Instant.parse("2026-10-07T10:00:00Z");
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final IntentRef reference = new IntentRef(UUID.randomUUID(), UUID.randomUUID());
    private MockRestServiceServer server;
    private HttpBotRecordingTransport client;
    @BeforeEach void setup() {
        properties.setClientSecret("synthetic-only");
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpBotRecordingTransport(properties, builder.build(), mapper, Clock.fixed(now, ZoneOffset.UTC));
    }
    @Test void exactServiceCredentialsAndNarrowPermissionAreUsedAndCached() throws Exception {
        token();
        for (int i = 0; i < 2; i++) server.expect(requestTo(properties.getOwnerBaseUrl() + "/api/v1/internal/bot-recording/inspect"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer synthetic-token"))
                .andExpect(headerDoesNotExist("Cookie")).andExpect(headerDoesNotExist("X-Company-Id"))
                .andExpect(jsonPath("$.intentId").value(reference.intentId().toString()))
                .andExpect(jsonPath("$.meetingId").value(reference.meetingId().toString()))
                .andRespond(withSuccess(mapper.writeValueAsString(snapshot()), MediaType.APPLICATION_JSON));
        assertThat(client.inspect(reference)).isEqualTo(snapshot()); client.inspect(reference); server.verify();
    }
    @ParameterizedTest @ValueSource(ints = {301, 302, 400, 401, 403, 404, 409, 429, 500})
    void onlyMissingAndConflictAreReflectedAndAllUpstreamDetailsAreRedacted(int status) {
        token();
        server.expect(requestTo(properties.getOwnerBaseUrl() + "/api/v1/internal/bot-recording/inspect"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).header("Location", "https://untrusted.example").body("secret-record"));
        assertThatThrownBy(() -> client.inspect(reference)).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode().value()).isEqualTo(status == 404 || status == 409 ? status : 503);
            assertThat(error.getMessage()).doesNotContain("secret-record", "untrusted");
        }); server.verify();
    }
    @ParameterizedTest @ValueSource(strings = {"broken", "{} {}", "oversized"})
    void malformedOrOversizeOwnerBodyFailsClosed(String body) {
        token(); server.expect(requestTo(properties.getOwnerBaseUrl() + "/api/v1/internal/bot-recording/inspect"))
                .andRespond(withSuccess(body.equals("oversized") ? "x".repeat(65537) : body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.inspect(reference)).isInstanceOf(ResponseStatusException.class); server.verify();
    }
    @Test void missingSecretSendsNothingEvenForWithdrawal() {
        properties.setClientSecret(""); assertThatThrownBy(() -> client.inspect(reference)).isInstanceOf(ResponseStatusException.class); server.verify();
    }
    @Test void invalidTokenMetadataDoesNotReachOwner() {
        server.expect(requestTo(properties.getTokenUrl())).andRespond(withSuccess("{\"access_token\":\"x\",\"expires_in\":999999,\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.inspect(reference)).isInstanceOf(ResponseStatusException.class); server.verify();
    }
    @Test void realHttpRedirectCannotForwardSecretOrBearer() throws Exception {
        // Blocking loopback fixture also works on Windows hosts where JDK HttpServer's Unix-domain selector pipe cannot open.
        AtomicInteger destinationHits = new AtomicInteger(), requests = new AtomicInteger();
        try (var listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            var responder = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (var socket = listener.accept()) {
                        socket.setSoTimeout(2000);
                        var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                        String line = input.readLine();
                        if (line == null) continue;
                        requests.incrementAndGet();
                        boolean destination = line.contains(" /destination ");
                        if (destination) destinationHits.incrementAndGet();
                        int remaining = 0;
                        while ((line = input.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) remaining = Integer.parseInt(line.substring(15).strip());
                        }
                        while (remaining-- > 0) if (input.read() < 0) throw new java.io.EOFException();
                        String response = destination ? "HTTP/1.1 500 Error\r\n" : "HTTP/1.1 307 Temporary Redirect\r\nLocation: /destination\r\n";
                        socket.getOutputStream().write((response + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    } catch (java.io.IOException ended) { if (!listener.isClosed()) throw new RuntimeException(ended); }
                }
            });
            responder.setDaemon(true); responder.start();
            properties.setTokenUrl("http://127.0.0.1:" + listener.getLocalPort() + "/token");
            var real = new HttpBotRecordingTransport(properties, RestClient.builder(), mapper);
            assertThatThrownBy(() -> real.inspect(reference)).isInstanceOf(ResponseStatusException.class);
            assertThat(requests.get()).isEqualTo(1); assertThat(destinationHits.get()).isZero();
        }
    }
    private void token() {
        server.expect(requestTo(properties.getTokenUrl())).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic bWVldGluZy1zZXJ2aWNlOnN5bnRoZXRpYy1vbmx5"))
                .andExpect(content().string("grant_type=client_credentials&audience=audit-event-consumer-service&permissions=audit%3Abot-recording%3Amanage"))
                .andRespond(withSuccess("{\"access_token\":\"synthetic-token\",\"expires_in\":60,\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
    }
    private Snapshot snapshot() {
        var actor = new Owner(35, 42, "https://issuer.example", "subject", reference.meetingId(), reference.meetingId(), "42", reference.meetingId(), reference.meetingId());
        return new Snapshot(1, "TEAMS_LIVE_TRANSCRIPTION", reference.intentId(),
                new Grant(reference.intentId(), reference.meetingId(), actor, "v1", "a".repeat(64), "tr-TR", now.plusSeconds(3600)), "GRANTED", 1, now, null, null);
    }
}
