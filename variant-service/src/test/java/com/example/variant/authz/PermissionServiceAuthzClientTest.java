package com.example.variant.authz;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import static org.junit.jupiter.api.Assertions.*;

class PermissionServiceAuthzClientTest {
    private HttpServer server;
    private PermissionServiceAuthzClient client;
    private final AtomicReference<String> observed = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{\"authzVersion\":42}";
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/authz/version", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            observed.set(auth);
            int status = "Bearer synthetic-test-token".equals(auth) ? responseStatus : 401;
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new PermissionServiceAuthzClient(WebClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void sendsCallerAuthenticationToRevisionEndpoint() {
        assertEquals(42L, client.getAuthzVersion("synthetic-test-token"));
        assertEquals("Bearer synthetic-test-token", observed.get());
    }
    @Test void missingAuthenticationFailsClosed() {
        assertThrows(AuthzDependencyUnavailableException.class, () -> client.getAuthzVersion(null));
        assertNull(observed.get());
    }
    @Test void rejectedAuthenticationFailsClosed() {
        assertThrows(AuthzDependencyUnavailableException.class, () -> client.getAuthzVersion("rejected-test-token"));
    }
    @Test void upstreamOutageDoesNotBecomeAUsableRevision() {
        responseStatus = 503;
        assertThrows(AuthzDependencyUnavailableException.class, () -> client.getAuthzVersion("synthetic-test-token"));
    }
    @Test void malformedRevisionDoesNotBecomeAUsableRevision() {
        responseBody = "{}";
        assertThrows(AuthzDependencyUnavailableException.class, () -> client.getAuthzVersion("synthetic-test-token"));
    }
}

