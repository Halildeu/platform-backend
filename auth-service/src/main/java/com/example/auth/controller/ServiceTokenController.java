package com.example.auth.controller;

import com.example.auth.serviceauth.ServiceClientsProperties;
import com.example.auth.serviceauth.ServiceMintPolicyProperties;
import com.example.auth.serviceauth.ServiceTokenProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/oauth2")
@Profile({"local", "dev"})
public class ServiceTokenController {

    private final ServiceTokenProvider serviceTokenProvider;
    private final ServiceClientsProperties clientsProperties;
    private final ServiceMintPolicyProperties mintPolicy;
    private final Environment environment;

    public ServiceTokenController(ServiceTokenProvider serviceTokenProvider,
                                  ServiceClientsProperties clientsProperties,
                                  ServiceMintPolicyProperties mintPolicy,
                                  Environment environment) {
        this.serviceTokenProvider = serviceTokenProvider;
        this.clientsProperties = clientsProperties;
        this.mintPolicy = mintPolicy;
        this.environment = environment;
    }

    @PostConstruct
    void ensureDefaultClients() {
        if (clientsProperties.getClients().isEmpty()) {
            String fallback = environment.getProperty("security.service-clients.user-service");
            if (fallback != null && !fallback.isBlank()) {
                clientsProperties.getClients().put("user-service", fallback);
            }
        }
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> token(@RequestHeader Map<String, String> headers,
                                   @RequestBody MultiValueMap<String, String> form) {
        org.slf4j.LoggerFactory.getLogger(ServiceTokenController.class)
                .debug("Token endpoint hit; headers={}", headers.keySet());
        String grantType = first(form, "grant_type");
        if (!"client_credentials".equals(grantType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
        }

        Credentials creds = resolveClientCredentials(headers, form);
        org.slf4j.LoggerFactory.getLogger(ServiceTokenController.class)
                .debug("Resolved credentials clientId={}, secretPresent={}", creds.clientId(), creds.clientSecret() != null);
        org.slf4j.LoggerFactory.getLogger(ServiceTokenController.class)
                .debug("Registered clients: {}", clientsProperties.getClients());
        if (!isAllowed(creds.clientId(), creds.clientSecret())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_client");
        }

        String audience = first(form, "audience");
        if (audience == null || audience.isBlank() || (!mintPolicy.getAllowedAudiences().isEmpty() && !mintPolicy.getAllowedAudiences().contains(audience))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_audience");
        }
        List<String> permissions = form.get("permissions");
        if (permissions != null && !mintPolicy.getAllowedPermissions().isEmpty()) {
            for (String p : permissions) {
                if (!mintPolicy.getAllowedPermissions().contains(p)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_permission");
                }
            }
        }

        enforceRateLimit(creds.clientId());
        String token = (permissions == null || permissions.isEmpty())
                ? serviceTokenProvider.getToken(audience, List.of())
                : serviceTokenProvider.getToken(audience, permissions);

        // expires_in: ServiceTokenProvider TTL bilgisi form dışından yönetiliyor; 60s varsayalım
        Map<String, Object> body = Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", 60
        );
        return ResponseEntity.ok(body);
    }

    private boolean isAllowed(String clientId, String clientSecret) {
        if (clientId == null || clientSecret == null) return false;
        String expected = clientsProperties.getClients().get(clientId);
        return expected != null && expected.equals(clientSecret);
    }

    private Credentials resolveClientCredentials(Map<String, String> headers, MultiValueMap<String, String> form) {
        String auth = headers.entrySet().stream()
                .filter(e -> "authorization".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (auth != null && auth.toLowerCase().startsWith("basic ")) {
            String b64 = auth.substring(6).trim();
            String decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx > 0) {
                return new Credentials(decoded.substring(0, idx), decoded.substring(idx + 1));
            }
        }
        String id = first(form, "client_id");
        String secret = first(form, "client_secret");
        return new Credentials(id, secret);
    }

    private String first(MultiValueMap<String, String> form, String key) {
        return Optional.ofNullable(form.getFirst(key)).orElse(null);
    }

    private record Credentials(String clientId, String clientSecret) {}

    // very simple in-memory rate limit (per clientId)
    private final java.util.concurrent.ConcurrentHashMap<String, Window> windows = new java.util.concurrent.ConcurrentHashMap<>();
    private void enforceRateLimit(String clientId) {
        int limit = Math.max(1, mintPolicy.getRateLimitPerMinute());
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(clientId, k -> new Window(now, 0));
        synchronized (w) {
            if (now - w.windowStartMs >= 60_000L) {
                w.windowStartMs = now;
                w.count = 0;
            }
            if (w.count >= limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
            }
            w.count++;
        }
    }
    private static class Window { Window(long s, int c){this.windowStartMs=s;this.count=c;} long windowStartMs; int count; }
}
