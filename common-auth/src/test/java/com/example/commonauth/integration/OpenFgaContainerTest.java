package com.example.commonauth.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenFGA Testcontainer integration test — verifies model loading and basic check.
 * CNS-20260411-003 PR3: Testcontainers OpenFGA with model.fga parity.
 *
 * Requires Docker running. Skipped in CI if Docker unavailable.
 */
@Testcontainers
@Tag("integration")
class OpenFgaContainerTest {

    @Container
    static final GenericContainer<?> openfga = new GenericContainer<>("openfga/openfga:v1.8.5")
            .withExposedPorts(8080, 8081)
            .withCommand("run")
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    private static String apiUrl;
    private static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void setup() {
        apiUrl = "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080);
    }

    @Test
    @DisplayName("OpenFGA container starts and healthz returns SERVING")
    void healthCheck() throws Exception {
        var resp = get("/healthz");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("SERVING"));
    }

    @Test
    @DisplayName("Can create store")
    void createStore() throws Exception {
        var resp = post("/stores", """
            {"name": "test-zanzibar"}
            """);
        assertEquals(201, resp.statusCode());
        assertTrue(resp.body().contains("test-zanzibar"));
    }

    @Test
    @DisplayName("Can create store and write authorization model with report type")
    void writeModel() throws Exception {
        // Create store
        var storeResp = post("/stores", """
            {"name": "model-test"}
            """);
        assertEquals(201, storeResp.statusCode());
        var storeId = extractJsonField(storeResp.body(), "id");

        // Write model (simplified — matches model.fga report type)
        var modelResp = post("/stores/" + storeId + "/authorization-models", """
            {
              "schema_version": "1.1",
              "type_definitions": [
                {"type": "user"},
                {
                  "type": "report",
                  "relations": {
                    "can_view": {
                      "difference": {
                        "base": {"this": {}},
                        "subtract": {"computedUserset": {"relation": "blocked"}}
                      }
                    },
                    "can_edit": {
                      "difference": {
                        "base": {"this": {}},
                        "subtract": {"computedUserset": {"relation": "blocked"}}
                      }
                    },
                    "blocked": {"this": {}}
                  },
                  "metadata": {
                    "relations": {
                      "can_view": {"directly_related_user_types": [{"type": "user"}]},
                      "can_edit": {"directly_related_user_types": [{"type": "user"}]},
                      "blocked": {"directly_related_user_types": [{"type": "user"}]}
                    }
                  }
                }
              ]
            }
            """);
        assertEquals(201, modelResp.statusCode());
        var modelId = extractJsonField(modelResp.body(), "authorization_model_id");
        assertNotNull(modelId);

        // Write tuple: user:alice can_view report:HR_REPORTS
        var writeResp = post("/stores/" + storeId + "/write", """
            {
              "writes": {
                "tuple_keys": [
                  {"user": "user:alice", "relation": "can_view", "object": "report:HR_REPORTS"}
                ]
              }
            }
            """);
        assertEquals(200, writeResp.statusCode());

        // Check: alice can_view HR_REPORTS → allowed
        var checkResp = post("/stores/" + storeId + "/check", """
            {
              "tuple_key": {"user": "user:alice", "relation": "can_view", "object": "report:HR_REPORTS"},
              "authorization_model_id": "%s"
            }
            """.formatted(modelId));
        assertEquals(200, checkResp.statusCode());
        assertTrue(checkResp.body().contains("\"allowed\":true"));

        // Check: bob can_view HR_REPORTS → denied (no tuple)
        var denyResp = post("/stores/" + storeId + "/check", """
            {
              "tuple_key": {"user": "user:bob", "relation": "can_view", "object": "report:HR_REPORTS"},
              "authorization_model_id": "%s"
            }
            """.formatted(modelId));
        assertEquals(200, denyResp.statusCode());
        assertTrue(denyResp.body().contains("\"allowed\":false"));
    }

    // --- HTTP helpers ---

    private static HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + path))
                .GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String extractJsonField(String json, String field) {
        // Simple extraction — not a full parser
        var pattern = "\"" + field + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
