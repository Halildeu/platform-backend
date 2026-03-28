package com.example.report.authz;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
@org.springframework.context.annotation.Profile("!conntest")
public class PermissionServiceClient implements PermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceClient.class);

    private final WebClient webClient;

    public PermissionServiceClient(WebClient.Builder loadBalancedWebClientBuilder,
                                   @Qualifier("plainWebClientBuilder") WebClient.Builder plainWebClientBuilder,
                                   @Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        WebClient.Builder selectedBuilder = requiresDirectHttp(baseUrl) ? plainWebClientBuilder : loadBalancedWebClientBuilder;
        this.webClient = selectedBuilder.baseUrl(baseUrl).build();
    }

    @Cacheable(value = "authzMe", key = "#jwt.subject")
    public AuthzMeResponse getAuthzMe(Jwt jwt) {
        return webClient.get()
                .uri("/api/v1/authz/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.getTokenValue())
                .retrieve()
                .bodyToMono(AuthzMeResponse.class)
                .onErrorResume(ex -> {
                    log.warn("Permission service error, denying access: {}", ex.getMessage());
                    return Mono.empty();
                })
                .defaultIfEmpty(new AuthzMeResponse())
                .block();
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
