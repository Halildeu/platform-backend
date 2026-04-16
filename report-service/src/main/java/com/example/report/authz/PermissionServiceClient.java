package com.example.report.authz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * PR6c (TB-11, 2026-04-15): DEPRECATED. report-service authz yetki karari
 * /authz/me full snapshot yerine OpenFgaAuthzService.check() ile yapmali
 * (D-008 constraint C-008). Consumer refactor gerekli — 3 controller etkilenir:
 * DashboardController, ReportController, ReportExportController.
 *
 * Follow-up: ayri story — "report-service authz refactor: PermissionResolver
 * usage'lari per-endpoint Zanzibar check() ile replace". Class kendisi
 * forRemoval=true; consumer'lar temizlenene kadar koruma icin tutuluyor.
 *
 * Bu PR'da sadece @Deprecated annotation eklendi, davranis degismedi.
 * TB-11 inventory'de "PR6c partial" olarak isaretlendi.
 */
@Deprecated(since = "2026-04-15", forRemoval = true)
@Component
@org.springframework.context.annotation.Profile("!conntest & !local & !dev")
public class PermissionServiceClient implements PermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceClient.class);

    private final WebClient webClient;

    public PermissionServiceClient(@Qualifier("plainWebClientBuilder") WebClient.Builder plainWebClientBuilder,
                                   @Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        // D7: @LoadBalanced kaldırıldı — K8s DNS ile plain builder yeterli.
        this.webClient = plainWebClientBuilder.baseUrl(baseUrl).build();
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
}
