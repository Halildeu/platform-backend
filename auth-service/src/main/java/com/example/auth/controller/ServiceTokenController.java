package com.example.auth.controller;

import com.example.auth.serviceauth.ServiceClientsProperties;
import com.example.auth.serviceauth.ServiceClientsProperties.ClientRegistration;
import com.example.auth.serviceauth.ServiceMintPolicyProperties;
import com.example.auth.serviceauth.ServiceTokenProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
public class ServiceTokenController {

    private static final String FAILED_AUTH_RATE_LIMIT_BUCKET = "auth-failure";

    private final ServiceTokenProvider serviceTokenProvider;
    private final ServiceClientsProperties clientsProperties;
    private final ServiceMintPolicyProperties mintPolicy;

    public ServiceTokenController(ServiceTokenProvider serviceTokenProvider,
                                  ServiceClientsProperties clientsProperties,
                                  ServiceMintPolicyProperties mintPolicy) {
        this.serviceTokenProvider = serviceTokenProvider;
        this.clientsProperties = clientsProperties;
        this.mintPolicy = mintPolicy;
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> token(@RequestHeader Map<String, String> headers,
                                   @RequestBody MultiValueMap<String, String> form) {
        String grantType = first(form, "grant_type");
        if (!"client_credentials".equals(grantType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
        }

        Credentials creds = resolveClientCredentials(headers, form);
        ClientRegistration registration = authenticate(creds.clientId(), creds.clientSecret());
        if (registration == null) {
            enforceRateLimit(
                    FAILED_AUTH_RATE_LIMIT_BUCKET,
                    mintPolicy.getFailedAuthRateLimitPerMinute());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_client");
        }

        String audience = first(form, "audience");
        if (audience == null
                || audience.isBlank()
                || !mintPolicy.getAllowedAudiences().contains(audience)
                || !registration.getAllowedAudiences().contains(audience)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_audience");
        }

        List<String> permissions = normalizePermissions(form.get("permissions"));
        if (registration.isRequireExplicitPermissions() && permissions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_permission");
        }
        Set<String> audiencePermissions = registration.getAllowedPermissionsByAudience().get(audience);
        for (String permission : permissions) {
            if (!mintPolicy.getAllowedPermissions().contains(permission)
                    || !registration.getAllowedPermissions().contains(permission)
                    || (!registration.getAllowedPermissionsByAudience().isEmpty()
                        && (audiencePermissions == null || !audiencePermissions.contains(permission)))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_permission");
            }
        }

        enforceRateLimit("client:" + creds.clientId(), mintPolicy.getRateLimitPerMinute());
        String token = serviceTokenProvider.getTokenForClient(
                creds.clientId(), audience, permissions);

        // expires_in: ServiceTokenProvider TTL bilgisi form dışından yönetiliyor; 60s varsayalım
        Map<String, Object> body = Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", 60
        );
        return ResponseEntity.ok(body);
    }

    private ClientRegistration authenticate(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank()
                || clientSecret == null || clientSecret.isBlank()) {
            return null;
        }
        ClientRegistration registration = clientsProperties.getClients().get(clientId);
        if (registration == null
                || registration.getSecret() == null
                || registration.getSecret().isBlank()) {
            return null;
        }
        byte[] expected = registration.getSecret().getBytes(StandardCharsets.UTF_8);
        byte[] presented = clientSecret.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented) ? registration : null;
    }

    private Credentials resolveClientCredentials(Map<String, String> headers, MultiValueMap<String, String> form) {
        String auth = headers.entrySet().stream()
                .filter(e -> "authorization".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (auth != null && auth.toLowerCase(Locale.ROOT).startsWith("basic ")) {
            try {
                String b64 = auth.substring(6).trim();
                String decoded = new String(
                        Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                int idx = decoded.indexOf(':');
                if (idx > 0) {
                    return new Credentials(decoded.substring(0, idx), decoded.substring(idx + 1));
                }
            } catch (IllegalArgumentException ignored) {
                return new Credentials(null, null);
            }
        }
        String id = first(form, "client_id");
        String secret = first(form, "client_secret");
        return new Credentials(id, secret);
    }

    private String first(MultiValueMap<String, String> form, String key) {
        return Optional.ofNullable(form.getFirst(key)).orElse(null);
    }

    private List<String> normalizePermissions(List<String> permissions) {
        if (permissions == null) {
            return List.of();
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_permission");
            }
            normalized.add(permission.trim());
        }
        return List.copyOf(normalized);
    }

    private record Credentials(String clientId, String clientSecret) {}

    private final java.util.concurrent.ConcurrentHashMap<String, Window> windows =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void enforceRateLimit(String bucket, int configuredLimit) {
        int limit = Math.max(1, configuredLimit);
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(bucket, ignored -> new Window(now, 0));
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

    private static class Window {
        private long windowStartMs;
        private int count;

        private Window(long windowStartMs, int count) {
            this.windowStartMs = windowStartMs;
            this.count = count;
        }
    }
}
