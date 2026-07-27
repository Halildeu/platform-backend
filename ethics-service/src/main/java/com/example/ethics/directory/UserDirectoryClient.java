package com.example.ethics.directory;

import com.example.ethics.config.UserDirectoryProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * ES-203/C — resolves Keycloak subjects to display names via user-service.
 *
 * <p>Subjects travel in a POST body, never in a URL: a path parameter would put
 * the KC UUID into every access log between here and the directory. For the
 * same reason nothing on this path — subject, name, count — is ever logged or
 * attached to a trace here, and the response is handed to the caller without
 * being cached. The only thing this class retains between calls is its own
 * service token.
 *
 * <p>Failure is a value, not an exception: the two consumers take opposite
 * paths (the assignment picker fails closed, the participants view degrades to
 * a null name) and that decision belongs to them, not to transport code.
 */
@Component
public class UserDirectoryClient {

    /** Whether the directory answered, and what it said. */
    public record Resolution(boolean available, Map<String, String> names) {
        public static Resolution unavailable() {
            return new Resolution(false, Map.of());
        }
    }

    private final UserDirectoryProperties properties;
    private final RestClient http;
    private volatile CachedToken cachedToken;

    @Autowired
    public UserDirectoryClient(UserDirectoryProperties properties, RestClient.Builder builder) {
        this(properties, buildHttp(properties, builder));
    }

    UserDirectoryClient(UserDirectoryProperties properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    private static RestClient buildHttp(UserDirectoryProperties properties, RestClient.Builder builder) {
        var requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(properties.httpTimeout());
        return builder.requestFactory(requestFactory).build();
    }

    public Resolution resolve(List<String> subjects) {
        if (subjects.isEmpty()) {
            return new Resolution(true, Map.of());
        }
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            return Resolution.unavailable();
        }
        try {
            Map<String, String> names = new HashMap<>();
            for (List<String> chunk : chunks(subjects)) {
                Entry[] entries = http.post()
                        .uri(properties.baseUrl() + "/api/users/internal/display-names")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .body(Map.of("subjects", chunk))
                        .retrieve()
                        .body(Entry[].class);
                if (entries == null) {
                    return Resolution.unavailable();
                }
                for (Entry entry : entries) {
                    if (entry.subject() != null && entry.displayName() != null && !entry.displayName().isBlank()) {
                        names.put(entry.subject(), entry.displayName());
                    }
                }
            }
            return new Resolution(true, Map.copyOf(names));
        } catch (RuntimeException transportOrHttp) {
            // Deliberately swallowed without logging: the exception message may
            // carry response fragments, and this path must not write subjects
            // or names anywhere durable.
            return Resolution.unavailable();
        }
    }

    private List<List<String>> chunks(List<String> subjects) {
        Set<String> unique = new LinkedHashSet<>(subjects);
        List<String> ordered = List.copyOf(unique);
        List<List<String>> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i += properties.batchLimit()) {
            result.add(ordered.subList(i, Math.min(ordered.size(), i + properties.batchLimit())));
        }
        return result;
    }

    private record Entry(String subject, String displayName) {
    }

    private String token() {
        Instant now = Instant.now();
        CachedToken local = cachedToken;
        if (local == null || !now.isBefore(local.refreshAfter())) {
            synchronized (this) {
                local = cachedToken;
                if (local == null || !now.isBefore(local.refreshAfter())) {
                    local = mintToken(now);
                    cachedToken = local;
                }
            }
        }
        return local.value();
    }

    private CachedToken mintToken(Instant now) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("audience", properties.tokenAudience());
        form.add("permissions", properties.tokenPermission());

        String basic = Base64.getEncoder().encodeToString(
                (properties.clientId() + ":" + properties.clientSecret())
                        .getBytes(StandardCharsets.UTF_8));
        Map<?, ?> response = http.post()
                .uri(properties.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null
                || !(response.get("access_token") instanceof String accessToken)
                || accessToken.isBlank()) {
            throw new IllegalStateException("User directory service token response was invalid");
        }
        long expiresIn = response.get("expires_in") instanceof Number number
                ? number.longValue()
                : 60L;
        long refreshSeconds = Math.max(5L, expiresIn - Math.min(10L, expiresIn / 2L));
        return new CachedToken(accessToken, now.plusSeconds(refreshSeconds));
    }

    private record CachedToken(String value, Instant refreshAfter) {
    }
}
