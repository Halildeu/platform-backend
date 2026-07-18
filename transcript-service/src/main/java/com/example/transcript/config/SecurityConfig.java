package com.example.transcript.config;

import com.example.transcript.security.AudienceValidator;
import com.example.transcript.security.ExpectedServiceClientValidator;
import com.example.transcript.security.FallbackJwtDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

@Configuration
@EnableMethodSecurity
@Profile("!local & !dev")
public class SecurityConfig {

    static final String SVC_CANONICAL_READ = "SVC_transcript:canonical:read";
    static final String SVC_SESSION_ERASE = "SVC_transcript:session:erase";
    static final String SVC_ANALYSIS_CAPABILITY_ISSUE =
            "SVC_transcript:analysis-job-capability:issue";

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain internalServiceSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .securityMatcher("/api/v1/internal/tenants/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAnyAuthority(
                        SVC_CANONICAL_READ, SVC_SESSION_ERASE,
                        SVC_ANALYSIS_CAPABILITY_ISSUE))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http,
                                                        JwtDecoder jwtDecoder,
                                                        JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .securityMatcher("/api/v1/admin/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasAnyAuthority("ROLE_ADMIN", "ROLE_TRANSCRIPT_ADMIN", "SCOPE_transcript")
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter)));
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

        NimbusJwtDecoder keycloakDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        keycloakDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(resolveAudiences(), resolveAllowedClientIds())));

        String serviceJwkSetUri = firstNonBlank(
                environment.getProperty("TRANSCRIPT_INTERNAL_SERVICE_JWT_JWK_SET_URI"),
                environment.getProperty("transcript.internal.service-jwt.jwk-set-uri"));
        if (!StringUtils.hasText(serviceJwkSetUri)) {
            return keycloakDecoder;
        }
        String serviceIssuer = resolveServiceIssuer();
        List<String> serviceAudiences = csvToList(firstNonBlank(
                environment.getProperty("TRANSCRIPT_INTERNAL_SERVICE_JWT_AUDIENCE"),
                environment.getProperty("transcript.internal.service-jwt.audience")));
        List<String> serviceClientIds = csvToList(firstNonBlank(
                environment.getProperty("TRANSCRIPT_INTERNAL_SERVICE_JWT_CLIENT_IDS"),
                environment.getProperty("transcript.internal.service-jwt.client-ids"),
                environment.getProperty("TRANSCRIPT_INTERNAL_SERVICE_JWT_CLIENT_ID"),
                environment.getProperty("transcript.internal.service-jwt.client-id")));
        JwtDecoder serviceDecoder = buildInternalServiceDecoder(
                serviceJwkSetUri, serviceIssuer, serviceAudiences, serviceClientIds);
        return new FallbackJwtDecoder(List.of(keycloakDecoder, serviceDecoder));
    }

    static JwtDecoder buildInternalServiceDecoder(
            String jwkSetUri,
            String issuer,
            Collection<String> audiences,
            Collection<String> expectedClientIds) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(buildInternalServiceValidator(issuer, audiences, expectedClientIds));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> buildInternalServiceValidator(
            String issuer,
            Collection<String> audiences,
            Collection<String> expectedClientIds) {
        if (!StringUtils.hasText(issuer) || !containsText(audiences) || !containsText(expectedClientIds)) {
            throw new IllegalArgumentException(
                    "Internal transcript service JWT issuer, audience and client allowlist are required");
        }
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        validators.add(new AudienceValidator(audiences));
        validators.add(new ExpectedServiceClientValidator(expectedClientIds));
        return new DelegatingOAuth2TokenValidator<>(validators.toArray(new OAuth2TokenValidator[0]));
    }

    private static boolean containsText(Collection<String> values) {
        return values != null && values.stream().anyMatch(StringUtils::hasText);
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        final String serviceIssuer = resolveServiceIssuer();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            LinkedHashSet<org.springframework.security.core.GrantedAuthority> authorities = new LinkedHashSet<>();

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null) {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) realmAccess.get("roles");
                if (roles != null) {
                    roles.stream()
                            .filter(role -> role != null && !role.isBlank())
                            .map(role -> "ROLE_" + role.trim().toUpperCase(Locale.ROOT))
                            .map(SimpleGrantedAuthority::new)
                            .forEach(authorities::add);
                }
            }

            if (serviceIssuer.equals(jwt.getClaimAsString("iss"))) {
                List<String> servicePermissions = jwt.getClaimAsStringList("perm");
                if (servicePermissions != null) {
                    servicePermissions.stream()
                            .filter(permission -> permission != null && !permission.isBlank())
                            .map(permission -> "SVC_" + permission.trim())
                            .map(SimpleGrantedAuthority::new)
                            .forEach(authorities::add);
                }
            }

            String scope = jwt.getClaimAsString("scope");
            if (scope != null) {
                Arrays.stream(scope.split(" "))
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .map(item -> "SCOPE_" + item)
                        .map(SimpleGrantedAuthority::new)
                        .forEach(authorities::add);
            }

            return authorities;
        });
        return converter;
    }

    private String resolveServiceIssuer() {
        return firstNonBlank(
                environment.getProperty("TRANSCRIPT_INTERNAL_SERVICE_JWT_ISSUER"),
                environment.getProperty("transcript.internal.service-jwt.issuer"),
                "auth-service");
    }

    private List<String> resolveAudiences() {
        String audienceCsv = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"),
                environment.getProperty("SECURITY_JWT_AUDIENCE"),
                environment.getProperty("security.jwt.audience"),
                environment.getProperty("spring.application.name"),
                "transcript-service"
        );
        return csvToList(audienceCsv);
    }

    private List<String> resolveAllowedClientIds() {
        String clientIdCsv = firstNonBlank(
                environment.getProperty("security.jwt.allowed-client-ids"),
                environment.getProperty("SECURITY_AUTH_ALLOWED_CLIENT_IDS"),
                "frontend,admin-cli,serban-web,account"
        );
        return csvToList(clientIdCsv);
    }

    private List<String> csvToList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
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
