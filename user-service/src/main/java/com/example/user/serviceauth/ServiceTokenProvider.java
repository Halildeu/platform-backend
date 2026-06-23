package com.example.user.serviceauth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class ServiceTokenProvider {

    private final ServiceTokenProperties serviceTokenProperties;
    private final ServiceTokenClientProperties clientProperties;
    private final String serviceId;
    private final WebClient webClient;

    // #734: cache per (audience|permissions) so the default permission-service
    // token and the notification-orchestrator/notify:intents:system token don't
    // evict each other.
    private final java.util.concurrent.ConcurrentHashMap<String, TokenCache> cacheByKey =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ServiceTokenProvider(ServiceTokenProperties serviceTokenProperties,
                                ServiceTokenClientProperties clientProperties,
                                @Qualifier("directWebClientBuilder") WebClient.Builder webClientBuilder,
                                @Value("${spring.application.name:user-service}") String serviceId) {
        this.serviceTokenProperties = serviceTokenProperties;
        this.clientProperties = clientProperties;
        this.serviceId = serviceId;
        this.webClient = webClientBuilder.build();
    }

    /** Token for the default configured audience + permissions. */
    public String getToken() {
        return getToken(serviceTokenProperties.getAudience(), serviceTokenProperties.getPermissions());
    }

    /**
     * #734: mint (and cache) a token for an EXPLICIT audience + permissions, so
     * the notification-orchestrator system-submit token (aud=notification-
     * orchestrator, perm=notify:intents:system) coexists with the default
     * permission-service token. Cache is keyed by audience+permissions.
     */
    public String getToken(String audience, List<String> permissions) {
        String key = audience + "|" + (permissions == null ? "" : String.join(",", permissions));
        Instant now = Instant.now();
        TokenCache localCache = cacheByKey.get(key);
        if (localCache == null || now.isAfter(localCache.refreshAfter())) {
            synchronized (cacheByKey) {
                localCache = cacheByKey.get(key);
                if (localCache == null || now.isAfter(localCache.refreshAfter())) {
                    localCache = mintFromAuth(now, audience, permissions);
                    cacheByKey.put(key, localCache);
                }
            }
        }
        return Objects.requireNonNull(localCache).value();
    }

    private TokenCache mintFromAuth(Instant now, String audience, List<String> permissions) {
        if (!clientProperties.isEnabled()) {
            throw new IllegalStateException("Service token mint client disabled");
        }

        long ttlSeconds = Math.max(30, serviceTokenProperties.getTtlSeconds());
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        Instant refreshAfter = expiresAt.minusSeconds(Math.min(10, ttlSeconds / 2));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("audience", audience);
        if (permissions != null) {
            for (String p : permissions) form.add("permissions", p);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String basic = Base64.getEncoder().encodeToString((clientProperties.getClientId() + ":" + clientProperties.getClientSecret()).getBytes(StandardCharsets.UTF_8));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + basic);

        Map<?, ?> body = webClient.post()
                .uri(clientProperties.getTokenUrl())
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        if (body == null) {
            throw new IllegalStateException("Service token mint failed: empty response");
        }
        Object token = body.get("access_token");
        if (!(token instanceof String str) || str.isBlank()) {
            throw new IllegalStateException("Service token missing in response");
        }
        return new TokenCache(str, refreshAfter, expiresAt);
    }

    private record TokenCache(String value, Instant refreshAfter, Instant expiresAt) {
    }
}
