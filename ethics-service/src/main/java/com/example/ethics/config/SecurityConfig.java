package com.example.ethics.config;

import com.example.ethics.security.PublicCredentialBoundaryFilter;
import com.example.ethics.security.PublicRateLimitFilter;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import java.util.Collection;
import java.util.Map;

@Configuration
@EnableConfigurationProperties({
    EthicsProperties.class,
    AuditDeliveryProperties.class,
    NotificationDeliveryProperties.class,
    PublicTenantProperties.class
})
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain publicApi(HttpSecurity http, PublicCredentialBoundaryFilter boundary,
            PublicRateLimitFilter rateLimit) throws Exception {
        http.securityMatcher("/api/v1/public/ethics/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Rate-limit must sit BEFORE the boundary filter — throttled
                // traffic never reaches credential checks or the JPA layer.
                .addFilterBefore(rateLimit, AnonymousAuthenticationFilter.class)
                .addFilterAfter(boundary, PublicRateLimitFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain staffApi(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http.securityMatcher("/api/v1/ethics/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_ethics:case:manage"))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    @Profile("!test")
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            EthicsProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(staffJwtValidator(issuer, properties));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> staffJwtValidator(String issuer, EthicsProperties properties) {
        var audience = new JwtClaimValidator<java.util.List<String>>("aud",
                values -> values != null && values.contains(properties.staffAudience()));
        var role = new JwtClaimValidator<Map<String, Object>>("realm_access", realmAccess -> {
            if (realmAccess == null) return false;
            Object rawRoles = realmAccess.get("roles");
            return rawRoles instanceof Collection<?> roles && roles.contains(properties.staffRole());
        });
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audience, role);
    }
}
