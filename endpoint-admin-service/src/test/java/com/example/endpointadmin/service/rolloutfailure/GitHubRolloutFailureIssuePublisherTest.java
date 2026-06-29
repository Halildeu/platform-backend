package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubRolloutFailureIssuePublisherTest {

    private HttpServer server;
    private int port;
    private int responseStatus = 201;
    private String responseBody = "{\"html_url\":\"https://github.com/Halildeu/platform-backend/issues/9001\",\"number\":9001}";
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/Halildeu/platform-backend/issues", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, responseStatus, responseBody);
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createIssuePostsExpectedGitHubPayloadAndParsesResponse() {
        GitHubRolloutFailureIssuePublisher publisher = new GitHubRolloutFailureIssuePublisher(
                props(4096), HttpClient.newHttpClient());

        RolloutFailureIssuePublisher.PublishedIssue issue = publisher.createIssue(issue());

        assertThat(issue.htmlUrl()).isEqualTo("https://github.com/Halildeu/platform-backend/issues/9001");
        assertThat(issue.number()).isEqualTo(9001);
        assertThat(authHeader.get()).isEqualTo("Bearer test-token");
        assertThat(requestBody.get())
                .contains("\"title\":\"Rollout Failure Escalation")
                .contains("\"body\":\"body")
                .contains("\"labels\":[\"rollout-failure\",\"class:INSTALLER_MSI\"]");
    }

    @Test
    void nonCreatedResponseFailsClosedWithoutLeakingToken() {
        responseStatus = 403;
        responseBody = "{\"message\":\"bad credentials\"}";
        GitHubRolloutFailureIssuePublisher publisher = new GitHubRolloutFailureIssuePublisher(
                props(4096), HttpClient.newHttpClient());

        assertThatThrownBy(() -> publisher.createIssue(issue()))
                .isInstanceOf(GitHubRolloutFailureIssuePublisher.GitHubIssuePublishException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageNotContaining("test-token");
    }

    @Test
    void oversizedResponseFailsClosedAtCap() {
        responseBody = "{\"html_url\":\"" + "x".repeat(200) + "\",\"number\":9001}";
        GitHubRolloutFailureIssuePublisher publisher = new GitHubRolloutFailureIssuePublisher(
                props(64), HttpClient.newHttpClient());

        assertThatThrownBy(() -> publisher.createIssue(issue()))
                .isInstanceOf(GitHubRolloutFailureIssuePublisher.GitHubIssuePublishException.class)
                .hasMessageContaining("capped");
    }

    private RolloutFailureGithubEscalationProperties props(int maxBytes) {
        return new RolloutFailureGithubEscalationProperties(false,
                "http://127.0.0.1:" + port, "Halildeu", "platform-backend", "test-token",
                "test-agent", Duration.ofSeconds(2), Duration.ofSeconds(2), maxBytes);
    }

    private static RolloutFailureEscalationResponse issue() {
        return new RolloutFailureEscalationResponse(
                "Rollout Failure Escalation — INSTALLER_MSI / wave-02",
                "body...",
                List.of("rollout-failure", "class:INSTALLER_MSI"),
                UUID.randomUUID());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
