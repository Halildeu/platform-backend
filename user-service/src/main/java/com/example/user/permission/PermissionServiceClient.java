package com.example.user.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.user.serviceauth.ServiceTokenProvider;

@Component
public class PermissionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceClient.class);

    private final WebClient webClient;
    private final ServiceTokenProvider tokenProvider;

    public PermissionServiceClient(@Qualifier("loadBalancedWebClientBuilder") WebClient.Builder webClientBuilder,
                                   @org.springframework.beans.factory.annotation.Value("${permission.service.base-url:http://permission-service}") String baseUrl,
                                   ServiceTokenProvider tokenProvider) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
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
            Boolean response = webClient.post()
                    .uri("/api/permissions/check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(tokenProvider.getToken()))
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Boolean.class)
                    .block();
            return Boolean.TRUE.equals(response);
        } catch (WebClientResponseException ex) {
            log.warn("PermissionService erişiminde HTTP hata: status={} message={}", ex.getStatusCode(), ex.getMessage());
            return false;
        } catch (Exception ex) {
            log.warn("PermissionService erişiminde hata: {}. Varsayılan olarak izin reddedilecek.", ex.getMessage());
            return false;
        }
    }
}
