package com.example.user.serviceauth;

import com.example.user.config.JwtProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
public class ServiceTokenProvider {

    private final ServiceTokenProperties serviceTokenProperties;
    private final ServiceTokenClientProperties clientProperties;
    private final String serviceId;
    private final RestTemplate restTemplate = new RestTemplate();

    private volatile TokenCache cache;

    public ServiceTokenProvider(ServiceTokenProperties serviceTokenProperties,
                                ServiceTokenClientProperties clientProperties,
                                @Value("${spring.application.name:user-service}") String serviceId) {
        this.serviceTokenProperties = serviceTokenProperties;
        this.clientProperties = clientProperties;
        this.serviceId = serviceId;
    }

    public String getToken() {
        TokenCache localCache = cache;
        Instant now = Instant.now();
        if (localCache == null || now.isAfter(localCache.refreshAfter())) {
            synchronized (this) {
                localCache = cache;
                if (localCache == null || now.isAfter(localCache.refreshAfter())) {
                    cache = mintFromAuth(now);
                    localCache = cache;
                }
            }
        }
        return Objects.requireNonNull(localCache).value();
    }

    private TokenCache mintFromAuth(Instant now) {
        if (!clientProperties.isEnabled()) {
            throw new IllegalStateException("Service token mint client disabled");
        }

        long ttlSeconds = Math.max(30, serviceTokenProperties.getTtlSeconds());
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        Instant refreshAfter = expiresAt.minusSeconds(Math.min(10, ttlSeconds / 2));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("audience", serviceTokenProperties.getAudience());
        List<String> permissions = serviceTokenProperties.getPermissions();
        if (permissions != null) {
            for (String p : permissions) form.add("permissions", p);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String basic = Base64.getEncoder().encodeToString((clientProperties.getClientId() + ":" + clientProperties.getClientSecret()).getBytes(StandardCharsets.UTF_8));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + basic);

        HttpEntity<MultiValueMap<String, String>> req = new HttpEntity<>(form, headers);
        ResponseEntity<Map> res = restTemplate.postForEntity(clientProperties.getTokenUrl(), req, Map.class);
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
            throw new IllegalStateException("Service token mint failed: " + res.getStatusCode());
        }
        Object token = res.getBody().get("access_token");
        if (!(token instanceof String str) || str.isBlank()) {
            throw new IllegalStateException("Service token missing in response");
        }
        return new TokenCache(str, refreshAfter, expiresAt);
    }

    private record TokenCache(String value, Instant refreshAfter, Instant expiresAt) {
    }
}
