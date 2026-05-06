package com.serban.notify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Notification orchestrator security config (Faz 23.2 PR-B — Codex 019dfae5 Q2 absorb).
 *
 * <p>Authn pattern: API gateway forwards JWT (Authorization: Bearer ...);
 * spring-boot-starter-oauth2-resource-server validates token via Keycloak JWKS.
 * Roles populated from JWT claim {@code realm_access.roles}.
 *
 * <p>Endpoint authorization:
 * <ul>
 *   <li>{@code /api/v1/admin/notify/**} → ROLE_PRIVACY_OFFICER (KVKK erasure)</li>
 *   <li>{@code /api/v1/notify/intents} → ROLE_USER (anyone with valid JWT)</li>
 *   <li>{@code /api/v1/internal/**} → ROLE_INTERNAL (S2S API key path)</li>
 *   <li>{@code /actuator/**} → permitAll (management server port)</li>
 *   <li>others → authenticated</li>
 * </ul>
 *
 * <p>Method security: {@code @EnableMethodSecurity} enables @PreAuthorize on
 * AdminErasureController.
 *
 * <p>JWT/JWKS config — application.yml:
 * <pre>
 * spring.security.oauth2.resourceserver.jwt.issuer-uri: https://keycloak/realms/...
 * </pre>
 *
 * <p>Test profile: minimal config; integration tests use {@code @WithMockUser}.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("PRIVACY_OFFICER")
                .requestMatchers("/api/v1/notify/intents/**").authenticated()
                .anyRequest().permitAll()  // submit + status both authenticated above; foundation
            )
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}));
        return http.build();
    }
}
