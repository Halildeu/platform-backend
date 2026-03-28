package com.example.user.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!local & !dev")
@SuppressWarnings("deprecation") // References legacy JwtAuthFilter intentionally for local/dev fallback
public class SecurityConfig {

    private final ObjectProvider<JwtAuthFilter> jwtAuthFilterProvider;
    private final ObjectProvider<LocalApiKeyAuthFilter> localApiKeyAuthFilterProvider;
    private final Environment environment;

    public SecurityConfig(ObjectProvider<JwtAuthFilter> jwtAuthFilterProvider,
                          ObjectProvider<LocalApiKeyAuthFilter> localApiKeyAuthFilterProvider,
                          Environment environment) {
        this.jwtAuthFilterProvider = jwtAuthFilterProvider;
        this.localApiKeyAuthFilterProvider = localApiKeyAuthFilterProvider;
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                // 1. CSRF korumasını devre dışı bırakıyoruz (Stateless API'ler için standarttır).
                .csrf(AbstractHttpConfigurer::disable)

                // 2. Session yönetimini STATELESS olarak ayarlıyoruz (JWT kullandığımız için).
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 3. İstekler için yetkilendirme kurallarını belirliyoruz.
                .authorizeHttpRequests(auth -> auth
                        // Herkese açık (public) endpoint'ler. Kimlik doğrulaması gerektirmez.
                        .requestMatchers(
                                "/api/users/by-email/**",
                                "/api/users/public/register",
                                "/actuator/**"
                        ).permitAll()
                        .requestMatchers("/api/users/internal/**").hasAuthority("PERM_users:internal")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                );

        // Local API Key filtresini bilinen bir filtreye göre konumlandır (BearerTokenAuthenticationFilter'dan önce)
        localApiKeyAuthFilterProvider.ifAvailable(filter ->
                http.addFilterBefore(filter, BearerTokenAuthenticationFilter.class));

        http
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(userJwtAuthenticationConverter())
                )
            );

        // DEV uyumluluk: HS tabanlı legacy kullanıcı JWT filtresi sadece local profil/flag açıkken eklenir
        if (isLocalProfileEnabled()) {
            jwtAuthFilterProvider.ifAvailable(filter ->
                http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
            );
        }

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // Birincil
        String primaryJwk = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
                environment.getProperty("SECURITY_JWT_USER_JWK_SET_URI"),
                environment.getProperty("SECURITY_JWT_JWK_SET_URI"),
                environment.getProperty("security.service-auth.jwk-set-uri"),
                "http://localhost:8081/realms/serban/protocol/openid-connect/certs"
        );
        String primaryIssuer = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
                environment.getProperty("SECURITY_JWT_ISSUER"),
                environment.getProperty("security.service-auth.issuer"),
                "http://localhost:8081/realms/serban"
        );
        java.util.List<String> audiences = resolveAudiences();

        // Opsiyonel ek issuer/jwk listeleri (virgülle ayrılmış)
        String extraJwks = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uris"),
                environment.getProperty("SECURITY_JWT_JWK_SET_URIS")
        );
        String extraIssuers = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuers"),
                environment.getProperty("SECURITY_JWT_ISSUERS")
        );

        java.util.List<JwtDecoder> decoders = new java.util.ArrayList<>();
        java.util.function.Function<String, OAuth2TokenValidator<Jwt>> validatorForIssuer = iss ->
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(iss),
                        new AudienceValidator(audiences)
                );

        NimbusJwtDecoder primary = NimbusJwtDecoder.withJwkSetUri(primaryJwk).build();
        primary.setJwtValidator(validatorForIssuer.apply(primaryIssuer));
        decoders.add(primary);

        // Extra issuers: each issuer uses the primary JWK endpoint
        // (same Keycloak instance, different hostname from frontend vs Docker)
        if (extraIssuers != null && !extraIssuers.isBlank()) {
            for (String iss : extraIssuers.split(",")) {
                String issuerTrimmed = iss.trim();
                if (issuerTrimmed.isEmpty() || issuerTrimmed.equals(primaryIssuer)) continue;
                NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(primaryJwk).build();
                d.setJwtValidator(validatorForIssuer.apply(issuerTrimmed));
                decoders.add(d);
            }
        }

        return token -> {
            for (JwtDecoder d : decoders) {
                try {
                    return d.decode(token);
                } catch (Exception ignore) {}
            }
            throw new org.springframework.security.oauth2.jwt.JwtException("No suitable decoder accepted the token");
        };
    }

    @Bean
    public JwtAuthenticationConverter userJwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            java.util.Set<org.springframework.security.core.GrantedAuthority> authorities = new java.util.LinkedHashSet<>();

            // 1. "permissions" claim (injected by frontend via permission-service)
            java.util.List<String> permissions = jwt.getClaimAsStringList("permissions");
            if (permissions != null) {
                permissions.forEach(p -> authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(p)));
            }

            // 2. "realm_access.roles" (Keycloak standard)
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null) {
                @SuppressWarnings("unchecked")
                java.util.List<String> roles = (java.util.List<String>) realmAccess.get("roles");
                if (roles != null) {
                    roles.forEach(r -> {
                        authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r.toUpperCase()));
                        // admin role → grant all common permissions
                        if ("admin".equalsIgnoreCase(r)) {
                            authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("user-read"));
                            authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("user-write"));
                            authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("user-manage"));
                            authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("admin"));
                        }
                    });
                }
            }

            // 3. "scope" claim fallback
            String scope = jwt.getClaimAsString("scope");
            if (scope != null) {
                for (String s : scope.split(" ")) {
                    if (!s.isBlank()) authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_" + s));
                }
            }

            return authorities;
        });
        return converter;
    }

    private boolean isLocalProfileEnabled() {
        String profiles = firstNonBlank(
                environment.getProperty("USER_SERVICE_PROFILES"),
                System.getProperty("spring.profiles.active", ""));
        if (profiles == null) return false;
        String p = profiles.toLowerCase();
        return p.contains("local");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private java.util.List<String> resolveAudiences() {
        String audienceProp = firstNonBlank(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"),
                environment.getProperty("security.service-auth.expected-audience"),
                environment.getProperty("SECURITY_JWT_AUDIENCE"),
                environment.getProperty("spring.application.name"),
                "user-service"
        );
        return splitCsv(audienceProp);
    }

    private static java.util.List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .toList();
    }
}
