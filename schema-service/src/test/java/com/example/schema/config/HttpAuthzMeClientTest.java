package com.example.schema.config;

import com.example.schema.config.AuthzMeClient.AuthzMeResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gitops#3608 — the permission-service transport maps every non-answer to a
 * deny-shaped result, and a projection without an identity is a non-answer.
 */
class HttpAuthzMeClientTest {

    private HttpServer server;
    private HttpAuthzMeClient client;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>("{\"userId\":\"42\"}");
    private final AtomicReference<Integer> versionStatus = new AtomicReference<>(200);
    private final AtomicReference<String> versionBody = new AtomicReference<>("{\"authzVersion\":17}");
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> seenVersionAuthorization = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(HttpAuthzMeClient.ME_PATH, exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, status.get(), body.get());
        });
        server.createContext(HttpAuthzMeClient.VERSION_PATH, exchange -> {
            seenVersionAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, versionStatus.get(), versionBody.get());
        });
        server.start();
        client = new HttpAuthzMeClient("http://127.0.0.1:" + server.getAddress().getPort() + "/",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String payload)
            throws java.io.IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void okProjectionIsParsedAndTheCallersBearerIsForwarded() {
        body.set("""
                {"userId":"42","superAdmin":false,"authzVersion":17,
                 "modules":{"REPORT":"MANAGE","THEME":"VIEW"},"allowedModules":["REPORT","THEME"],
                 "somethingNew":{"ignored":true}}
                """);

        AuthzMeResult r = client.fetch("tok-123");

        assertThat(r.kind()).isEqualTo(AuthzMeResult.Kind.OK);
        assertThat(r.superAdmin()).isFalse();
        assertThat(r.modules()).isEqualTo(Map.of("REPORT", "MANAGE", "THEME", "VIEW"));
        assertThat(r.allowedModules()).containsExactly("REPORT", "THEME");
        assertThat(r.authzVersion()).isEqualTo(17L);
        assertThat(seenAuthorization.get()).isEqualTo("Bearer tok-123");
    }

    @Test
    void superAdminFlagIsRead() {
        body.set("{\"userId\":\"1\",\"superAdmin\":true}");
        assertThat(client.fetch("t").superAdmin()).isTrue();
    }

    @Test
    void positiveLookingBodyWithoutIdentityIsNoAnswer() {
        for (String payload : new String[] {
                "{\"superAdmin\":true}",
                "{\"modules\":{\"REPORT\":\"VIEW\"}}",
                "{\"userId\":\"\",\"modules\":{\"REPORT\":\"MANAGE\"}}",
                "{\"userId\":null,\"superAdmin\":true}"}) {
            body.set(payload);
            AuthzMeResult r = client.fetch("t");
            assertThat(r.kind()).as(payload).isEqualTo(AuthzMeResult.Kind.UNAVAILABLE);
            assertThat(r.detail()).isEqualTo("no_identity");
            assertThat(r.superAdmin()).isFalse();
            assertThat(r.modules()).isEmpty();
        }
    }

    @Test
    void unauthorizedIsRejectedNotUnavailable() {
        status.set(401);
        body.set("{\"error\":\"unauthorized\"}");
        AuthzMeResult r = client.fetch("t");
        assertThat(r.kind()).isEqualTo(AuthzMeResult.Kind.REJECTED);
        assertThat(r.detail()).isEqualTo("http_401");
    }

    @Test
    void serverErrorIsUnavailable() {
        status.set(500);
        AuthzMeResult r = client.fetch("t");
        assertThat(r.kind()).isEqualTo(AuthzMeResult.Kind.UNAVAILABLE);
        assertThat(r.detail()).isEqualTo("http_500");
    }

    @Test
    void unparsableBodyIsUnavailable() {
        body.set("<html>not json");
        AuthzMeResult r = client.fetch("t");
        assertThat(r.kind()).isEqualTo(AuthzMeResult.Kind.UNAVAILABLE);
        assertThat(r.detail()).isEqualTo("parse");
    }

    @Test
    void unreachableHostIsUnavailable() {
        server.stop(0);
        AuthzMeResult r = client.fetch("t");
        assertThat(r.kind()).isEqualTo(AuthzMeResult.Kind.UNAVAILABLE);
        assertThat(r.detail()).startsWith("transport:");
        assertThat(client.fetchVersion("t")).isEmpty();
    }

    @Test
    void versionIsReadWithTheCallersBearer() {
        assertThat(client.fetchVersion("tok-9")).isEqualTo(OptionalLong.of(17L));
        assertThat(seenVersionAuthorization.get()).isEqualTo("Bearer tok-9");
    }

    @Test
    void versionRefusedOrMalformedIsEmptyNotZero() {
        versionStatus.set(401);
        assertThat(client.fetchVersion("t")).isEmpty();

        versionStatus.set(200);
        versionBody.set("{\"authzVersion\":\"not-a-number\"}");
        assertThat(client.fetchVersion("t")).isEmpty();
    }
}
