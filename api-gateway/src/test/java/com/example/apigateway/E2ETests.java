package com.example.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ApiGatewayApplication.class)
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
        "spring.main.web-application-type=reactive"
})
class E2ETests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, RegisteredUser> USERS = new ConcurrentHashMap<>();
    private static MockWebServer stub;

    @LocalServerPort
    int port;

    @BeforeAll
    static void startStubServer() throws IOException {
        USERS.clear();
        USERS.put("admin@example.com", new RegisteredUser("Admin User", "admin@example.com", "admin1234", List.of("ROLE_ADMIN"), List.of("VIEW_USERS", "MANAGE_USERS")));

        stub = new MockWebServer();
        stub.setDispatcher(new GatewayE2EDispatcher());
        stub.start();
    }

    @AfterAll
    static void stopStubServer() throws IOException {
        USERS.clear();
        if (stub != null) {
            stub.shutdown();
        }
    }

    @DynamicPropertySource
    static void routeProps(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "auth-service-route");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> stub.url("/").toString());
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/auth/**");

        registry.add("spring.cloud.gateway.server.webflux.routes[1].id", () -> "user-service-route");
        registry.add("spring.cloud.gateway.server.webflux.routes[1].uri", () -> stub.url("/").toString());
        registry.add("spring.cloud.gateway.server.webflux.routes[1].predicates[0]", () -> "Path=/api/users/**");
    }

    @Test
    void fullUserRegistrationAndLoginFlow() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;

        String newUserEmail = "e2e-user@example.com";
        String password = "password123";
        long companyId = 1L;

        String adminToken = getAdminToken(companyId);

        given()
            .contentType(ContentType.JSON)
            .header("Authorization", "Bearer " + adminToken)
            .header("X-Company-Id", companyId)
            .body(Map.of(
                    "name", "E2E User",
                    "email", newUserEmail,
                    "password", password
            ))
        .when()
            .post("/api/users/register")
        .then()
            .statusCode(201)
            .body("email", equalTo(newUserEmail));

        String userToken = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                    "email", newUserEmail,
                    "password", password,
                    "companyId", companyId
            ))
        .when()
            .post("/api/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
            .body("permissions", hasItem("VIEW_USERS"))
            .extract()
            .path("token");

        given()
            .header("Authorization", "Bearer " + userToken)
            .header("X-Company-Id", companyId)
        .when()
            .get("/api/users/me/authorities")
        .then()
            .statusCode(200)
            .body("username", equalTo(newUserEmail))
            .body("authorities", hasItem("ROLE_USER"));

        given()
            .header("Authorization", "Bearer " + adminToken)
            .header("X-Company-Id", companyId)
        .when()
            .get("/api/users/all")
        .then()
            .statusCode(200)
            .body("$", not(empty()))
            .body("email", hasItem("admin@example.com"))
            .body("email", hasItem(newUserEmail));
    }

    private String getAdminToken(long companyId) {
        return given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                    "email", "admin@example.com",
                    "password", "admin1234",
                    "companyId", companyId
            ))
        .when()
            .post("/api/auth/login")
        .then()
            .statusCode(200)
            .body("token", notNullValue())
            .extract()
            .path("token");
    }

    private static Map<String, Object> readJsonBody(RecordedRequest request) {
        try {
            String body = request.getBody().readUtf8();
            if (body == null || body.isBlank()) {
                return Map.of();
            }
            return OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
        } catch (IOException exception) {
            throw new IllegalStateException("Request body okunamadi", exception);
        }
    }

    private static MockResponse jsonResponse(int statusCode, Object body) {
        try {
            return new MockResponse()
                    .setResponseCode(statusCode)
                    .addHeader("Content-Type", "application/json")
                    .setBody(OBJECT_MAPPER.writeValueAsString(body));
        } catch (IOException exception) {
            throw new IllegalStateException("JSON response yazilamadi", exception);
        }
    }

    private static String extractBearerToken(RecordedRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length());
    }

    private static final class GatewayE2EDispatcher extends Dispatcher {

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = Objects.requireNonNullElse(request.getPath(), "");

            if (path.startsWith("/api/auth/login")) {
                return handleLogin(request);
            }
            if (path.startsWith("/api/users/register")) {
                return handleRegister(request);
            }
            if (path.startsWith("/api/users/me/authorities")) {
                return handleAuthorities(request);
            }
            if (path.startsWith("/api/users/all")) {
                return handleUsersAll(request);
            }

            return jsonResponse(404, Map.of("error", "not_found"));
        }

        private MockResponse handleLogin(RecordedRequest request) {
            Map<String, Object> body = readJsonBody(request);
            String email = Objects.toString(body.get("email"), "");
            String password = Objects.toString(body.get("password"), "");
            RegisteredUser user = USERS.get(email);
            if (user == null || !user.password().equals(password)) {
                return jsonResponse(401, Map.of("error", "invalid_credentials"));
            }

            String token = tokenFor(email);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("token", token);
            response.put("permissions", user.permissions());
            response.put("user", Map.of("email", user.email(), "name", user.name()));
            return jsonResponse(200, response);
        }

        private MockResponse handleRegister(RecordedRequest request) {
            String token = extractBearerToken(request);
            if (!"admin-token".equals(token)) {
                return jsonResponse(403, Map.of("error", "forbidden"));
            }

            Map<String, Object> body = readJsonBody(request);
            String email = Objects.toString(body.get("email"), "");
            String name = Objects.toString(body.get("name"), "");
            String password = Objects.toString(body.get("password"), "");

            RegisteredUser user = new RegisteredUser(
                    name,
                    email,
                    password,
                    List.of("ROLE_USER"),
                    List.of("VIEW_USERS")
            );
            USERS.put(email, user);

            return jsonResponse(201, Map.of(
                    "id", 2,
                    "name", name,
                    "email", email
            ));
        }

        private MockResponse handleAuthorities(RecordedRequest request) {
            String token = extractBearerToken(request);
            String email = emailForToken(token);
            RegisteredUser user = USERS.get(email);
            if (user == null) {
                return jsonResponse(401, Map.of("error", "unauthorized"));
            }

            return jsonResponse(200, Map.of(
                    "username", user.email(),
                    "authorities", user.roles()
            ));
        }

        private MockResponse handleUsersAll(RecordedRequest request) {
            String token = extractBearerToken(request);
            if (!"admin-token".equals(token)) {
                return jsonResponse(403, Map.of("error", "forbidden"));
            }

            List<Map<String, Object>> payload = USERS.values().stream()
                    .map(user -> Map.<String, Object>of(
                            "email", user.email(),
                            "name", user.name()
                    ))
                    .toList();
            return jsonResponse(200, payload);
        }

        private String tokenFor(String email) {
            if ("admin@example.com".equals(email)) {
                return "admin-token";
            }
            return "token::" + email;
        }

        private String emailForToken(String token) {
            if ("admin-token".equals(token)) {
                return "admin@example.com";
            }
            if (token != null && token.startsWith("token::")) {
                return token.substring("token::".length());
            }
            return null;
        }
    }

    private record RegisteredUser(
            String name,
            String email,
            String password,
            List<String> roles,
            List<String> permissions
    ) {}
}
