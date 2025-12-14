package com.example.auth.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PermissionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public PermissionServiceClient(RestTemplate restTemplate,
                                   @org.springframework.beans.factory.annotation.Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public Set<String> getPermissions(Long userId, Long companyId) {
        try {
            String url = baseUrl + "/api/permissions/assignments?userId=" + userId;
            if (companyId != null) {
                url += "&companyId=" + companyId;
            }

            ResponseEntity<PermissionAssignmentResponse[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    PermissionAssignmentResponse[].class
            );
            PermissionAssignmentResponse[] body = response.getBody();
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
        } catch (RestClientException ex) {
            log.warn("PermissionService'den izinler alınamadı: {}. Varsayılan olarak boş liste dönülecek.", ex.getMessage());
            return Collections.emptySet();
        }
    }
}
