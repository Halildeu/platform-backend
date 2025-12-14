package com.example.user.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.example.user.serviceauth.ServiceTokenProvider;

@Component
public class PermissionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ServiceTokenProvider tokenProvider;

    public PermissionServiceClient(RestTemplate restTemplate,
                                   @org.springframework.beans.factory.annotation.Value("${permission.service.base-url:http://permission-service}") String baseUrl,
                                   ServiceTokenProvider tokenProvider) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.tokenProvider = tokenProvider;
    }

    public boolean hasPermission(Long userId,
                                 Long companyId,
                                 Long projectId,
                                 Long warehouseId,
                                 String action) {
        PermissionCheckRequest request = new PermissionCheckRequest();
        request.setUserId(userId);
        request.setCompanyId(companyId);
        request.setProjectId(projectId);
        request.setWarehouseId(warehouseId);
        request.setAction(action);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(tokenProvider.getToken());
            HttpEntity<PermissionCheckRequest> entity = new HttpEntity<>(request, headers);
            ResponseEntity<Boolean> response = restTemplate.postForEntity(
                    baseUrl + "/api/permissions/check",
                    entity,
                    Boolean.class
            );
            return Boolean.TRUE.equals(response.getBody());
        } catch (RestClientException ex) {
            log.warn("PermissionService erişiminde hata: {}. Varsayılan olarak izin reddedilecek.", ex.getMessage());
            return false;
        }
    }
}
