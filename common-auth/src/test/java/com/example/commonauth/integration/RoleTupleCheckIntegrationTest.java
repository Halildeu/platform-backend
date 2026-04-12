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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E integration test for the Zanzibar authorization chain:
 * role -> tuple write -> check -> data access.
 *
 * Uses Testcontainers OpenFGA with the project's model.fga types
 * (module, company, report) to verify:
 *   1. ALLOW: tuple write -> check -> true
 *   2. DENY: blocked relation -> check -> false
 *   3. SCOPE: company viewer -> listObjects -> returns company
 *   4. NO_RELATION: no tuple -> check -> false
 *
 * Requires Docker running. Skipped in CI if Docker unavailable.
 */
@Testcontainers
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoleTupleCheckIntegrationTest {

    @Container
    static final GenericContainer<?> openfga = new GenericContainer<>("openfga/openfga:v1.8.5")
            .withExposedPorts(8080, 8081)
            .withCommand("run")
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    private static String apiUrl;
    private static String storeId;
    private static String modelId;
    private static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void setup() throws Exception {
        apiUrl = "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080);

        // Create store
        var storeResp = post("/stores", """
            {"name": "zanzibar-e2e-test"}
            """);
        assertEquals(201, storeResp.statusCode(), "Store creation failed: " + storeResp.body());
        storeId = extractJsonField(storeResp.body(), "id");
        assertNotNull(storeId, "Store ID must not be null");

        // Write authorization model matching project model.fga types:
        // user, module (can_view/can_edit/can_manage/blocked), company (viewer/member/admin/manager),
        // report (can_view/can_edit/blocked)
        var modelResp = post("/stores/" + storeId + "/authorization-models", """
            {
              "schema_version": "1.1",
              "type_definitions": [
                {"type": "user"},
                {
                  "type": "module",
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
                    "can_manage": {
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
                      "can_manage": {"directly_related_user_types": [{"type": "user"}]},
                      "blocked": {"directly_related_user_types": [{"type": "user"}]}
                    }
                  }
                },
                {
                  "type": "company",
                  "relations": {
                    "admin": {"this": {}},
                    "manager": {"this": {}},
                    "member": {
                      "union": {
                        "child": [
                          {"this": {}},
                          {"computedUserset": {"relation": "manager"}},
                          {"computedUserset": {"relation": "admin"}}
                        ]
                      }
                    },
                    "viewer": {
                      "union": {
                        "child": [
                          {"this": {}},
                          {"computedUserset": {"relation": "member"}}
                        ]
                      }
                    }
                  },
                  "metadata": {
                    "relations": {
                      "admin": {"directly_related_user_types": [{"type": "user"}]},
                      "manager": {"directly_related_user_types": [{"type": "user"}]},
                      "member": {"directly_related_user_types": [{"type": "user"}]},
                      "viewer": {"directly_related_user_types": [{"type": "user"}]}
                    }
                  }
                },
                {
                  "type": "report",
                  "relations": {
                    "can_edit": {
                      "difference": {
                        "base": {"this": {}},
                        "subtract": {"computedUserset": {"relation": "blocked"}}
                      }
                    },
                    "can_view": {
                      "union": {
                        "child": [
                          {"difference": {
                            "base": {"this": {}},
                            "subtract": {"computedUserset": {"relation": "blocked"}}
                          }},
                          {"difference": {
                            "base": {"computedUserset": {"relation": "can_edit"}},
                            "subtract": {"computedUserset": {"relation": "blocked"}}
                          }}
                        ]
                      }
                    },
                    "blocked": {"this": {}}
                  },
                  "metadata": {
                    "relations": {
                      "can_edit": {"directly_related_user_types": [{"type": "user"}]},
                      "can_view": {"directly_related_user_types": [{"type": "user"}]},
                      "blocked": {"directly_related_user_types": [{"type": "user"}]}
                    }
                  }
                }
              ]
            }
            """);
        assertEquals(201, modelResp.statusCode(), "Model creation failed: " + modelResp.body());
        modelId = extractJsonField(modelResp.body(), "authorization_model_id");
        assertNotNull(modelId, "Model ID must not be null");
    }

    // --- Scenario 1: ALLOW ---
    @Test
    @Order(1)
    @DisplayName("ALLOW: Write can_view tuple -> check returns true")
    void allowScenario_tupleWriteThenCheckAllowed() throws Exception {
        // Write tuple: user:1 can_view module:ACCESS
        var writeResp = post("/stores/" + storeId + "/write", """
            {
              "writes": {
                "tuple_keys": [
                  {"user": "user:1", "relation": "can_view", "object": "module:ACCESS"}
                ]
              }
            }
            """);
        assertEquals(200, writeResp.statusCode(), "Tuple write failed: " + writeResp.body());

        // Check: user:1 can_view module:ACCESS -> allowed
        var checkResp = post("/stores/" + storeId + "/check", """
            {
              "tuple_key": {"user": "user:1", "relation": "can_view", "object": "module:ACCESS"},
              "authorization_model_id": "%s"
            }
            """.formatted(modelId));
        assertEquals(200, checkResp.statusCode());
        assertTrue(checkResp.body().contains("\"allowed\":true"),
                "Expected allowed=true for user:1 can_view module:ACCESS, got: " + checkResp.body());
    }

    // --- Scenario 2: DENY (blocked relation) ---
    @Test
    @Order(2)
    @DisplayName("DENY: Write blocked tuple -> can_view check returns false")
    void denyScenario_blockedRelationDeniesAccess() throws Exception {
        // Write can_view tuple first (grant access)
        var writeGrant = post("/stores/" + storeId + "/write", """
            {
              "writes": {
                "tuple_keys": [
                  {"user": "user:2", "relation": "can_view", "object": "module:THEME"}
                ]
              }
            }
            """);
        assertEquals(200, writeGrant.statusCode(), "Grant tuple write failed: " + writeGrant.body());

        // Write blocked tuple (deny-wins semantics)
        var writeBlock = post("/stores/" + storeId + "/write", """
            {
              "writes": {
                "tuple_keys": [
                  {"user": "user:2", "relation": "blocked", "object": "module:THEME"}
                ]
              }
            }
            """);
        assertEquals(200, writeBlock.statusCode(), "Block tuple write failed: " + writeBlock.body());

        // Check: user:2 can_view module:THEME -> denied (blocked wins)
        var checkResp = post("/stores/" + storeId + "/check", """
            {
              "tuple_key": {"user": "user:2", "relation": "can_view", "object": "module:THEME"},
              "authorization_model_id": "%s"
            }
            """.formatted(modelId));
        assertEquals(200, checkResp.statusCode());
        assertTrue(checkResp.body().contains("\"allowed\":false"),
                "Expected allowed=false for blocked user:2 on module:THEME, got: " + checkResp.body());
    }

    // --- Scenario 3: SCOPE (company viewer -> listObjects) ---
    @Test
    @Order(3)
    @DisplayName("SCOPE: Write company viewer tuple -> listObjects returns the company")
    void scopeScenario_companyViewerListObjects() throws Exception {
        // Write viewer tuple: user:3 viewer company:42
        var writeResp = post("/stores/" + storeId + "/write", """
            {
              "writes": {
                "tuple_keys": [
                  {"user": "user:3", "relation": "viewer", "object": "company:42"}
                ]
              }
            }
            """);
        assertEquals(200, writeResp.statusCode(), "Scope tuple write failed: " + writeResp.body());

        // ListObjects: user:3 viewer company -> should include company:42
        var listResp = post("/stores/" + storeId + "/list-objects", """
            {
              "user": "user:3",
              "relation": "viewer",
              "type": "company",
              "authorization_model_id": "%s"
            }
            """.formatted(modelId));
        assertEquals(200, listResp.statusCode(), "ListObjects failed: " + listResp.body());
        assertTrue(listResp.body().contains("company:42"),
                "Expected company:42 in listObjects result, got: " + listResp.body());

        // Negative: user:3 should NOT see company:99 (no tuple)
        assertFalse(listResp.body().contains("company:99"),
                "user:3 should not have access to company:99");
    }

    // --- Scenario 4: NO_RELATION ---
    @Test
    @Order(4)
    @DisplayName("NO_RELATION: Check without any tuple -> returns false")
    void noRelationScenario_noTupleReturnsFalse() throws Exception {
        // Check: user:999 (no tuples written) can_view module:INVENTORY -> denied
        var checkResp = post("/stores/" + storeId + "/check", """
            {
              "tuple_key": {"user": "user:999", "relation": "can_view", "object": "module:INVENTORY"},
              "authorization_model_id": "%s"
            }
            """.formatted(modelId));
        assertEquals(200, checkResp.statusCode());
        assertTrue(checkResp.body().contains("\"allowed\":false"),
                "Expected allowed=false for user:999 with no tuples, got: " + checkResp.body());
    }

    // --- Additional: Verify deny-wins on report type ---
    @Test
    @Order(5)
    @DisplayName("DENY on report: blocked user cannot view report even with can_view tuple")
    void denyOnReportType() throws Exception {
        // Write can_view on report
        var writeGrant = post("/stores/" + storeId + "/write", """
            {
              "writes": {
                "tuple_keys": [
                  {"user": "user:5", "relation": "can_view", "object": "report:SALES_REPORT"}
                ]
              }
            }
            """);
        assertEquals(200, writeGrant.statusCode());

        // Block user on same report
        var writeBlock = post("/stores/" + storeId + "/write", """
            {
              "writes": {
                "tuple_keys": [
                  {"user": "user:5", "relation": "blocked", "object": "report:SALES_REPORT"}
                ]
              }
            }
            """);
        assertEquals(200, writeBlock.statusCode());

        // Check: blocked -> can_view should be false
        var checkResp = post("/stores/" + storeId + "/check", """
            {
              "tuple_key": {"user": "user:5", "relation": "can_view", "object": "report:SALES_REPORT"},
              "authorization_model_id": "%s"
            }
            """.formatted(modelId));
        assertEquals(200, checkResp.statusCode());
        assertTrue(checkResp.body().contains("\"allowed\":false"),
                "Expected allowed=false for blocked user:5 on report:SALES_REPORT, got: " + checkResp.body());
    }

    // --- HTTP helpers (same pattern as OpenFgaContainerTest) ---

    private static HttpResponse<String> post(String path, String body) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String extractJsonField(String json, String field) {
        var pattern = "\"" + field + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
