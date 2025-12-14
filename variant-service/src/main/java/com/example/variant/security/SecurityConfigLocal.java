package com.example.variant.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@Profile({"local", "dev"})
public class SecurityConfigLocal {

    private final Environment environment;

    public SecurityConfigLocal(Environment environment) {
        this.environment = environment;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain securityFilterChainLocal(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // Token varsa parse edilsin; yoksa da erişim engellenmesin.
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        String jwkSetUri = firstNonBlank(
            environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
            environment.getProperty("SECURITY_JWT_JWK_SET_URI"),
            "http://localhost:8081/realms/serban/protocol/openid-connect/certs"
        );
        String issuer = firstNonBlank(
            environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
            environment.getProperty("SECURITY_JWT_ISSUER"),
            "http://localhost:8081/realms/serban"
        );
        String audience = firstNonBlank(
            firstFromList(environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences")),
            environment.getProperty("security.jwt.audience"),
            environment.getProperty("spring.application.name"),
            "variant-service"
        );

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new AudienceValidator(audience)
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private String firstFromList(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int idx = value.indexOf(',');
        return idx >= 0 ? value.substring(0, idx).trim() : value.trim();
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
