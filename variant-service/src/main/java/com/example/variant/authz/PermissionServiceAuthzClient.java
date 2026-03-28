package com.example.variant.authz;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
public class PermissionServiceAuthzClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceAuthzClient.class);

    private final WebClient webClient;

    @Autowired
    public PermissionServiceAuthzClient(@Qualifier("loadBalancedWebClientBuilder") WebClient.Builder loadBalancedWebClientBuilder,
                                        @Qualifier("plainWebClientBuilder") WebClient.Builder plainWebClientBuilder,
                                        @Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        WebClient.Builder selectedBuilder = requiresDirectHttp(baseUrl) ? plainWebClientBuilder : loadBalancedWebClientBuilder;
        this.webClient = selectedBuilder.baseUrl(baseUrl).build();
    }

    public PermissionServiceAuthzClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://permission-service").build();
    }

    public AuthzMeResponse getAuthzMe(String bearerToken) {
        try {
            WebClient.RequestHeadersSpec<?> request = webClient.get()
                    .uri("/api/v1/authz/me");
            if (bearerToken != null && !bearerToken.isBlank()) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            }
            return request.retrieve()
                    .bodyToMono(AuthzMeResponse.class)
                    .onErrorResume(ex -> {
                        log.warn("permission-service /authz/me fetch failed: {}", ex.getMessage());
                        return Mono.empty();
                    })
                    .defaultIfEmpty(new AuthzMeResponse())
                    .block();
        } catch (WebClientResponseException ex) {
            log.warn("permission-service /authz/me returned {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            return new AuthzMeResponse();
        } catch (Exception ex) {
            log.warn("permission-service /authz/me error", ex);
            return new AuthzMeResponse();
        }
    }

    private boolean requiresDirectHttp(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }
            return "localhost".equalsIgnoreCase(host)
                    || host.contains(".")
                    || host.contains(":");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
