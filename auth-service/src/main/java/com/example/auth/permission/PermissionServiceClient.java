package com.example.auth.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PermissionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceClient.class);

    private final WebClient webClient;

    public PermissionServiceClient(@Qualifier("loadBalancedWebClientBuilder") WebClient.Builder webClientBuilder,
                                   @org.springframework.beans.factory.annotation.Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
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
                    .map(String::toUpperCase)
                    .collect(Collectors.toSet());
        } catch (WebClientResponseException ex) {
            log.warn("PermissionService izin çözümünde HTTP hata: status={} message={}", ex.getStatusCode(), ex.getMessage());
            return Collections.emptySet();
        } catch (Exception ex) {
            log.warn("PermissionService'den izinler alınamadı: {}. Varsayılan olarak boş liste dönülecek.", ex.getMessage());
            return Collections.emptySet();
        }
    }
}
