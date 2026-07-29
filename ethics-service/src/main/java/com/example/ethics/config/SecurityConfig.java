package com.example.ethics.config;

import com.example.ethics.security.PublicCredentialBoundaryFilter;
import com.example.ethics.security.PublicRateLimitFilter;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.DispatcherType;
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
    EthicsSlaProperties.class,
    EthicsSlaCalendarProperties.class,
    EthicsProperties.class,
    EvidenceProperties.class,
    AuditDeliveryProperties.class,
    NotificationDeliveryProperties.class,
    PublicTenantProperties.class,
    UserDirectoryProperties.class
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

    /**
     * Everything the two chains above do not claim, denied.
     *
     * <p>The {@code ERROR} dispatch is permitted, and that is a fix rather than a loosening.
     * A request to a path that does not exist produces a 404, Spring forwards it to
     * {@code /error} to be rendered, and {@code /error} is outside the staff matcher — so it
     * landed here and {@code denyAll} turned it into a body-less 403. "No such endpoint" and
     * "you may not use this endpoint" became the same answer.
     *
     * <p>That cost real time: a frontend shipped ahead of its service asked for
     * {@code /cases/{id}/timeline} before the endpoint existed, and the 403 read as an
     * authorization fault. Ingress, edge, NetPol and token claims were all eliminated before
     * the cause turned out to be that the endpoint was simply not there yet.
     *
     * <p>Nothing is disclosed by allowing it. The 404 is about a path the caller already
     * typed, and it carries no information about who they are or what exists behind the
     * authorization boundary. <b>Case identity is a different matter and is untouched:</b> a
     * case the caller may not see still answers 404 rather than 403, deliberately, so that
     * probing cannot reveal whether a case exists. That decision lives in the gate, runs on
     * the {@code REQUEST} dispatch, and is unaffected by how the response is rendered.
     */
    @Bean
    @Order(3)
    SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
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
