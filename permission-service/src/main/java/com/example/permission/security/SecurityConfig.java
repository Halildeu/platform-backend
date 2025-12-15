package com.example.permission.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
// STORY-0002: Backend Keycloak JWT Hardening
public class SecurityConfig {

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   CompositeJwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJsonError(response, HttpStatus.UNAUTHORIZED, "unauthorized", "JWT token zorunludur."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJsonError(response, HttpStatus.FORBIDDEN, "forbidden", "Bu işlem için yetkiniz bulunmuyor."))
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                );

        return http.build();
    }

    private void writeJsonError(HttpServletResponse response,
                                HttpStatus status,
                                String code,
                                String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = String.format("{\"error\":\"%s\",\"message\":\"%s\"}", code, message);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        String jwkSetUri = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
                environment.getProperty("security.jwt.user-jwk-set-uri"),
                environment.getProperty("security.jwt.jwk-set-uri"),
                "http://localhost:8081/realms/serban/protocol/openid-connect/certs"
        );
        String issuer = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
                environment.getProperty("security.jwt.issuer"),
                "http://localhost:8081/realms/serban"
        );
        String audience = firstNonBlank(
                firstFromList(environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences")),
                environment.getProperty("security.jwt.audience"),
                environment.getProperty("spring.application.name"),
                "permission-service"
        );

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        decoder.setJwtValidator(buildServiceValidator(issuer, audience));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> buildServiceValidator(String issuer, String audience) {
        java.util.List<OAuth2TokenValidator<Jwt>> validators = new java.util.ArrayList<>();
        if (StringUtils.hasText(issuer)) {
            validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        } else {
            validators.add(JwtValidators.createDefault());
        }
        if (StringUtils.hasText(audience)) {
            validators.add(new AudienceValidator(audience));
        }
        return new DelegatingOAuth2TokenValidator<>(
                validators.toArray(new OAuth2TokenValidator[0])
        );
    }

    @Bean
    public CompositeJwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter serviceAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        serviceAuthoritiesConverter.setAuthoritiesClaimName("perm");
        serviceAuthoritiesConverter.setAuthorityPrefix("PERM_");

        JwtAuthenticationConverter serviceConverter = new JwtAuthenticationConverter();
        serviceConverter.setPrincipalClaimName("svc");
        serviceConverter.setJwtGrantedAuthoritiesConverter(serviceAuthoritiesConverter);

        JwtGrantedAuthoritiesConverter userAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        userAuthoritiesConverter.setAuthoritiesClaimName("permissions");
        userAuthoritiesConverter.setAuthorityPrefix("");

        JwtAuthenticationConverter userConverter = new JwtAuthenticationConverter();
        userConverter.setPrincipalClaimName("sub");
        userConverter.setJwtGrantedAuthoritiesConverter(userAuthoritiesConverter);

        // Varsayılan davranış: kullanıcı token'ı olduğunda user converter, aksi halde service converter
        return new CompositeJwtAuthenticationConverter(serviceConverter, userConverter);
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
