package com.example.auth.permission;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.auth.serviceauth.ServiceTokenProvider;

import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PermissionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceClient.class);
    private static final String PERMISSION_SERVICE_AUDIENCE = "permission-service";
    private static final String REQUIRED_PERMISSION = "permissions:read";

    private final WebClient webClient;
    private final ServiceTokenProvider serviceTokenProvider;

    public PermissionServiceClient(@Qualifier("loadBalancedWebClientBuilder") WebClient.Builder webClientBuilder,
                                   @Qualifier("plainWebClientBuilder") WebClient.Builder plainWebClientBuilder,
                                   @org.springframework.beans.factory.annotation.Value("${permission.service.base-url:http://permission-service}") String baseUrl,
                                   ServiceTokenProvider serviceTokenProvider) {
        WebClient.Builder selectedBuilder = requiresDirectHttp(baseUrl) ? plainWebClientBuilder : webClientBuilder;
        this.webClient = selectedBuilder.baseUrl(baseUrl).build();
        this.serviceTokenProvider = serviceTokenProvider;
    }

    public Set<String> getPermissions(Long userId, Long companyId) {
        try {
            PermissionAssignmentResponse[] body = webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/api/permissions/assignments")
                                .queryParam("userId", userId);
                        if (companyId != null) {
                            uriBuilder.queryParam("companyId", companyId);
                        }
                        return uriBuilder.build();
                    })
                    .headers(headers -> headers.setBearerAuth(
                            serviceTokenProvider.getToken(PERMISSION_SERVICE_AUDIENCE, List.of(REQUIRED_PERMISSION))
                    ))
                    .retrieve()
                    .bodyToMono(PermissionAssignmentResponse[].class)
                    .block();
            if (body == null) {
                return Collections.emptySet();
            }
            return List.of(body).stream()
                    .filter(PermissionAssignmentResponse::isActive)
                    .flatMap(assignment -> {
                        Set<String> permissions = assignment.getPermissions();
                        return permissions == null ? Collections.<String>emptySet().stream() : permissions.stream();
                    })
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (WebClientResponseException ex) {
            log.warn("PermissionService izin çözümünde HTTP hata: status={} message={}", ex.getStatusCode(), ex.getMessage());
            return Collections.emptySet();
        } catch (Exception ex) {
            log.warn("PermissionService'den izinler alınamadı: {}. Varsayılan olarak boş liste dönülecek.", ex.getMessage());
            return Collections.emptySet();
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
