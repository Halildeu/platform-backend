package com.example.schema.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AuthzMeClient} over {@code java.net.http} — same transport common-auth's
 * {@code RemoteAuthzVersionProvider} uses against permission-service.
 */
public class HttpAuthzMeClient implements AuthzMeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAuthzMeClient.class);
    static final String PATH = "/api/v1/authz/me";

    private final URI uri;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public HttpAuthzMeClient(String baseUrl, Duration connectTimeout, Duration requestTimeout) {
        this.uri = URI.create(stripTrailingSlash(baseUrl) + PATH);
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public AuthzMeResult fetch(String bearerToken) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("authz/me transport failure: {}", e.toString());
            return AuthzMeResult.unavailable("transport:" + e.getClass().getSimpleName());
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            return AuthzMeResult.rejected(status);
        }
        if (status != 200) {
            log.warn("authz/me returned {}", status);
            return AuthzMeResult.unavailable("http_" + status);
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            Map<String, String> modules = new LinkedHashMap<>();
            JsonNode modulesNode = root.path("modules");
            if (modulesNode.isObject()) {
                modulesNode.fields().forEachRemaining(f -> modules.put(f.getKey(), f.getValue().asText()));
            }
            List<String> allowed = new ArrayList<>();
            JsonNode allowedNode = root.path("allowedModules");
            if (allowedNode.isArray()) {
                allowedNode.forEach(n -> allowed.add(n.asText()));
            }
            Long version = root.path("authzVersion").isNumber() ? root.path("authzVersion").asLong() : null;
            return AuthzMeResult.ok(root.path("superAdmin").asBoolean(false), modules, allowed, version);
        } catch (Exception e) {
            log.warn("authz/me body unparsable: {}", e.toString());
            return AuthzMeResult.unavailable("parse");
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
