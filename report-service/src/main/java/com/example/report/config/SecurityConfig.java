package com.example.report.config;

import com.example.report.security.AudienceValidator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@Profile("!local & !dev")
public class SecurityConfig {

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    @Profile("conntest")
    public SecurityFilterChain conntestSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Profile("!conntest")
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/v1/reports/**").authenticated()
                        .requestMatchers("/api/v1/dashboards/**").authenticated()
                        .anyRequest().permitAll()
                )
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    @Bean
    @Profile("!conntest")
    public JwtDecoder jwtDecoder() {
        String jwkSetUri = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
                environment.getProperty("SECURITY_JWT_JWK_SET_URI"),
                environment.getProperty("security.jwt.jwk-set-uri"),
                "http://localhost:8081/realms/serban/protocol/openid-connect/certs"
        );
        String issuer = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
                environment.getProperty("SECURITY_JWT_ISSUER"),
                environment.getProperty("security.jwt.issuer"),
                "http://localhost:8081/realms/serban"
        );
        List<String> audiences = resolveAudiences();

        // Primary decoder
        NimbusJwtDecoder primaryDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        if ("none".equalsIgnoreCase(issuer)) {
            primaryDecoder.setJwtValidator(new AudienceValidator(audiences));
            return primaryDecoder;
        }

        primaryDecoder.setJwtValidator(buildValidator(issuer, audiences));

        // Secondary issuers — Docker internal vs localhost Keycloak
        String secondaryIssuer = deriveSecondaryIssuer(issuer);
        if (secondaryIssuer != null) {
            NimbusJwtDecoder secondaryDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            secondaryDecoder.setJwtValidator(buildValidator(secondaryIssuer, audiences));
            return fallbackDecoder(List.of(primaryDecoder, secondaryDecoder));
        }

        return primaryDecoder;
    }

    private OAuth2TokenValidator<Jwt> buildValidator(String issuer, List<String> audiences) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(audiences)
        );
    }

    /**
     * Docker/localhost issuer eşleştirmesi:
     * keycloak:8080 ↔ localhost:8081 arası otomatik fallback.
     */
    private String deriveSecondaryIssuer(String primaryIssuer) {
        if (primaryIssuer.contains("keycloak:8080")) {
            return primaryIssuer.replace("keycloak:8080", "localhost:8081")
                                .replace("http://keycloak", "http://localhost");
        }
        if (primaryIssuer.contains("localhost:8081")) {
            return primaryIssuer.replace("localhost:8081", "keycloak:8080")
                                .replace("http://localhost", "http://keycloak");
        }
        return null;
    }

    private JwtDecoder fallbackDecoder(List<NimbusJwtDecoder> decoders) {
        return token -> {
            JwtException lastError = null;
            for (NimbusJwtDecoder decoder : decoders) {
                try {
                    return decoder.decode(token);
                } catch (JwtException e) {
                    lastError = e;
                }
            }
            throw lastError != null ? lastError : new JwtException("No suitable decoder accepted the token");
        };
    }

    private List<String> resolveAudiences() {
        String audienceCsv = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"),
                environment.getProperty("SECURITY_JWT_AUDIENCE"),
                environment.getProperty("security.jwt.audience"),
                environment.getProperty("spring.application.name"),
                "report-service"
        );
        if (audienceCsv == null || audienceCsv.isBlank() || "none".equalsIgnoreCase(audienceCsv)) {
            return List.of();
        }
        return Arrays.stream(audienceCsv.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
