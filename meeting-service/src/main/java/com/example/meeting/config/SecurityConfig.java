package com.example.meeting.config;

import com.example.meeting.security.AudienceValidator;
import com.example.meeting.security.ExpectedServiceClientValidator;
import com.example.meeting.security.FallbackJwtDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
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

/**
 * Resource-server security for meeting-service. Three ordered filter chains:
 *
 * <ol>
 *   <li>{@code @Order(1)} internal service-to-service chain — ai#244 BE-1b.
 *       {@code /api/v1/internal/meetings/**} gated on the
 *       {@code SVC_meeting:analysis-result:write} authority (auth-service
 *       SERVICE token only).</li>
 *   <li>{@code @Order(2)} admin chain — {@code /api/v1/admin/**} gated on the
 *       coarse admin authorities; {@code @RequireModule} OpenFGA is the
 *       fine-grained gate at the controllers.</li>
 *   <li>{@code @Order(3)} default chain — actuator {@code permitAll}, everything
 *       else authenticated.</li>
 * </ol>
 *
 * <p>Adapted from endpoint-admin-service {@code SecurityConfig}; the BE-1b
 * internal chain + {@link FallbackJwtDecoder} + service-token converter mapping
 * are ported from notification-orchestrator {@code #734}.
 */
@Configuration
@EnableMethodSecurity
@org.springframework.context.annotation.Profile("!local & !dev")
public class SecurityConfig {

    /**
     * Authority that the internal analysis-result ingestion path requires.
     * auth-service mints the raw {@code perm} {@code meeting:analysis-result:write};
     * {@link #jwtAuthenticationConverter()} maps it to this {@code SVC_}-prefixed
     * authority ONLY for tokens whose {@code iss} is the configured service
     * issuer, so a Keycloak USER token can never obtain it.
     */
    static final String SVC_ANALYSIS_RESULT_WRITE = "SVC_meeting:analysis-result:write";

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * ai#244 BE-1b — internal service-to-service chain (highest priority).
     * meeting-ai-service (the ingestion caller, arriving in BE-1c) reaches
     * {@code /api/v1/internal/meetings/**} with an auth-service-minted SERVICE
     * token. A Keycloak USER token can never satisfy this gate: the
     * {@code SVC_} authority is granted by the converter ONLY when the token
     * issuer is the service issuer, and the env-gated service decoder is the
     * ONLY decoder that will validate a service-issuer token.
     *
     * <p>BE-1b mounts NO production controller under this path — the ingestion
     * endpoint arrives in BE-1c. The chain is wired ahead of the surface so the
     * security boundary exists before the endpoint does (chain-only production;
     * the exercising controller is test-scoped).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain internalServiceSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .securityMatcher("/api/v1/internal/meetings/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasAuthority(SVC_ANALYSIS_RESULT_WRITE))
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
                        .anyRequest().hasAnyAuthority("ROLE_ADMIN", "ROLE_MEETING_ADMIN", "SCOPE_meeting")
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

        JwtDecoder keycloakDecoder = buildDecoder(jwkSetUri, issuer, resolveAudiences(), resolveAllowedClientIds());

        // ai#244 BE-1b: optional SECONDARY decoder for auth-service SERVICE
        // tokens used by the internal ingestion path (/api/v1/internal/meetings/**).
        // Env-gated — active ONLY when the service JWK-set URI is configured.
        // Absent/blank ⇒ FAIL-CLOSED: only the Keycloak decoder exists, so no
        // service-issuer token can ever decode and the converter therefore never
        // mints the SVC_ authority the internal chain requires. Ported
        // FallbackJwtDecoder pattern from notification-orchestrator #734: try
        // Keycloak first, then the service issuer.
        String serviceJwkSetUri = firstNonBlank(
                environment.getProperty("MEETING_INTERNAL_SERVICE_JWT_JWK_SET_URI"),
                environment.getProperty("meeting.internal.service-jwt.jwk-set-uri")
        );
        if (!StringUtils.hasText(serviceJwkSetUri)) {
            return keycloakDecoder;
        }
        String serviceIssuer = resolveServiceIssuer();
        List<String> serviceAudiences = csvToList(firstNonBlank(
                environment.getProperty("MEETING_INTERNAL_SERVICE_JWT_AUDIENCE"),
                environment.getProperty("meeting.internal.service-jwt.audience"),
                "meeting-service"
        ));
        List<String> serviceClientIds = csvToList(firstNonBlank(
                environment.getProperty("MEETING_INTERNAL_SERVICE_JWT_CLIENT_ID"),
                environment.getProperty("meeting.internal.service-jwt.client-id")
        ));
        if (serviceClientIds.isEmpty()) {
            throw new IllegalStateException(
                    "internal service JWT client id is required when the service JWKS is enabled");
        }
        JwtDecoder serviceDecoder = buildInternalServiceDecoder(
                serviceJwkSetUri, serviceIssuer, serviceAudiences, serviceClientIds);
        return new FallbackJwtDecoder(List.of(keycloakDecoder, serviceDecoder));
    }

    static JwtDecoder buildInternalServiceDecoder(String jwkSetUri,
                                                   String issuer,
                                                   Collection<String> audiences,
                                                   Collection<String> expectedClientIds) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(buildInternalServiceValidator(
                issuer, audiences, expectedClientIds));
        return decoder;
    }

    /**
     * Build a NimbusJwtDecoder over a JWK-set URI with the issuer + audience
     * validators. Package-visible + static so security tests can reuse the exact
     * production validator wiring (see {@link #buildServiceValidator}).
     */
    static JwtDecoder buildDecoder(String jwkSetUri,
                                   String issuer,
                                   Collection<String> audiences,
                                   Collection<String> allowedClientIds) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(buildServiceValidator(issuer, audiences, allowedClientIds));
        return decoder;
    }

    /**
     * Issuer-claim assertion + audience/authorized-party allow-list. Extracted
     * (mirrors notification-orchestrator #734) so the same validator logic backs
     * both the Keycloak and the service decoder and can be exercised directly in
     * tests against real, signed tokens.
     */
    static OAuth2TokenValidator<Jwt> buildServiceValidator(String issuer,
                                                           Collection<String> audiences,
                                                           Collection<String> allowedClientIds) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        if (StringUtils.hasText(issuer)) {
            validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        } else {
            validators.add(JwtValidators.createDefault());
        }
        if ((audiences != null && !audiences.isEmpty())
                || (allowedClientIds != null && !allowedClientIds.isEmpty())) {
            validators.add(new AudienceValidator(audiences, allowedClientIds));
        }
        return new DelegatingOAuth2TokenValidator<>(
                validators.toArray(new OAuth2TokenValidator[0]));
    }

    /**
     * Internal SERVICE tokens must satisfy issuer AND audience AND expected-client identity.
     * The general {@link AudienceValidator} intentionally supports audience OR authorized
     * party for Keycloak user tokens, so it cannot be reused for this stronger boundary.
     */
    static OAuth2TokenValidator<Jwt> buildInternalServiceValidator(
            String issuer,
            Collection<String> audiences,
            Collection<String> expectedClientIds) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        if (StringUtils.hasText(issuer)) {
            validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        } else {
            validators.add(JwtValidators.createDefault());
        }
        validators.add(new AudienceValidator(audiences));
        validators.add(new ExpectedServiceClientValidator(expectedClientIds));
        return new DelegatingOAuth2TokenValidator<>(
                validators.toArray(new OAuth2TokenValidator[0]));
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        // ai#244 BE-1b: the service issuer the converter trusts for perm→SVC_
        // mapping. Read once at bean construction; default "auth-service"
        // (auth-service ServiceTokenProvider stamps iss=auth-service).
        final String serviceIssuer = resolveServiceIssuer();

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            LinkedHashSet<GrantedAuthority> authorities = new LinkedHashSet<>();

            // Keycloak realm_access.roles → ROLE_* (existing behaviour preserved).
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null) {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) realmAccess.get("roles");
                if (roles != null) {
                    roles.stream()
                            .filter(role -> role != null && !role.isBlank())
                            // Locale.ROOT: without it, a Turkish-locale JVM
                            // (tr_TR) maps "admin" → "ADMİN" (dotted capital I),
                            // so ROLE_ADMIN never matches and admin access breaks
                            // — a latent bug this BE-1b work surfaced. ROOT keeps
                            // the mapping deterministic across every deployment.
                            .map(role -> "ROLE_" + role.trim().toUpperCase(Locale.ROOT))
                            .map(SimpleGrantedAuthority::new)
                            .forEach(authorities::add);
                }
            }

            // scope → SCOPE_* (existing behaviour preserved).
            String scope = jwt.getClaimAsString("scope");
            if (scope != null) {
                Arrays.stream(scope.split(" "))
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .map(item -> "SCOPE_" + item)
                        .map(SimpleGrantedAuthority::new)
                        .forEach(authorities::add);
            }

            // ai#244 BE-1b: auth-service SERVICE-token `perm` claim → SVC_*
            // authority, gated STRICTLY on iss == serviceIssuer. A Keycloak USER
            // token always carries a different iss (enforced by the Keycloak
            // decoder's issuer validator), so it can NEVER gain a SVC_ authority
            // even if it somehow presented a `perm` claim. This iss guard is the
            // linchpin that keeps the internal chain unreachable by user tokens.
            if (serviceIssuer.equals(jwt.getClaimAsString("iss"))) {
                List<String> servicePerms = jwt.getClaimAsStringList("perm");
                if (servicePerms != null) {
                    servicePerms.stream()
                            .filter(p -> p != null && !p.isBlank())
                            .map(p -> "SVC_" + p.trim())
                            .map(SimpleGrantedAuthority::new)
                            .forEach(authorities::add);
                }
            }

            return authorities;
        });
        return converter;
    }

    private String resolveServiceIssuer() {
        return firstNonBlank(
                environment.getProperty("MEETING_INTERNAL_SERVICE_JWT_ISSUER"),
                environment.getProperty("meeting.internal.service-jwt.issuer"),
                "auth-service"
        );
    }

    private List<String> resolveAudiences() {
        String audienceCsv = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"),
                environment.getProperty("SECURITY_JWT_AUDIENCE"),
                environment.getProperty("security.jwt.audience"),
                environment.getProperty("spring.application.name"),
                "meeting-service"
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
