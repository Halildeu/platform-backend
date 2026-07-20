package com.example.ethics.security;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Server-side permission-service boundary for every staff request.
 *
 * <p>The Keycloak role/scope proves that the token may ask for the product;
 * current permission-service state decides whether it still has ETHIC=MANAGE.
 * Transport, HTTP, decode, identity, and projection failures all deny access.
 */
@Component
public class EthicsEntitlementVerifier {
    private final RestClient permissionService;

    @Autowired
    public EthicsEntitlementVerifier(
            RestClient.Builder builder,
            @Value("${ethics.permission-service-base-url:http://permission-service:8090}") String baseUrl,
            @Value("${ethics.permission-service-timeout:PT3S}") Duration timeout) {
        var httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.permissionService = builder.clone()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }

    EthicsEntitlementVerifier(RestClient permissionService) {
        this.permissionService = permissionService;
    }

    public boolean hasManageEntitlement(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) return false;
        try {
            AuthzMeResponse response = permissionService.get()
                    .uri("/api/v1/authz/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(AuthzMeResponse.class);
            return isExactEthicManage(response);
        } catch (RuntimeException unavailableOrInvalid) {
            // Do not disclose dependency health or retain a previous allow.
            return false;
        }
    }

    static boolean isExactEthicManage(AuthzMeResponse response) {
        if (response == null
                || response.userId() == null
                || response.userId().isBlank()
                || response.subscriberId() == null
                || !response.userId().equals(response.subscriberId().toString())
                || !Boolean.FALSE.equals(response.superAdmin())
                || response.roles() == null
                || response.modules() == null
                || response.allowedModules() == null
                || response.permissions() == null) {
            return false;
        }
        return response.roles().contains("ETIK_SPEAK_MANAGER")
                && "MANAGE".equals(response.modules().get("ETHIC"))
                && response.allowedModules().contains("ETHIC")
                && response.permissions().contains("ETHIC");
    }

    record AuthzMeResponse(
            String userId,
            Long subscriberId,
            Boolean superAdmin,
            List<String> roles,
            Map<String, String> modules,
            List<String> allowedModules,
            List<String> permissions) {}
}
