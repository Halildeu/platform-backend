package com.example.audiogateway.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * JWT validation chain — Keycloak SSO realm reuse (auth-service pattern).
 *
 * <p>Fail-closed by default: any non-public endpoint requires a valid JWT.
 * Public: {@code /actuator/health} (+ k8s probe sub-paths {@code /liveness},
 * {@code /readiness} — kubelet probes can't attach Authorization headers),
 * {@code /actuator/info}, {@code /actuator/prometheus}.
 *
 * <p>tenantId / userId / roles are derived from JWT claims AFTER validation —
 * NEVER trusted from client payload (Codex {@code 019e879c} explicit RED).
 *
 * <p><b>Faz 24 (#716, cross-AI 019ee16b) — shared-realm hardening, DEFAULT-OFF:</b>
 * adds (1) an audience validator (token {@code aud} must target this resource) and
 * (2) a capability role ({@code resource_access.<resourceClientId>.roles}) extracted
 * into authorities and optionally required. Both gated by
 * {@link AudioGatewaySecurityProperties} flags ({@code enforceAudience},
 * {@code requireAudioRecordRole}), default {@code false} — no behaviour change until
 * the migration enforce-flip (mappers-first; see #716 runbook). The decoder keeps the
 * dual issuer-uri + jwk-set-uri contract (internal JWKS in-cluster, no external fetch).
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(AudioGatewaySecurityProperties.class)
public class SecurityConfig {

    private final AudioGatewaySecurityProperties props;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    public SecurityConfig(final AudioGatewaySecurityProperties props) {
        this.props = props;
    }

    @Bean
    public SecurityWebFilterChain securityFilterChain(final ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> {
                    ex.pathMatchers(
                            "/actuator/health",
                            "/actuator/health/liveness",
                            "/actuator/health/readiness",
                            "/actuator/info",
                            "/actuator/prometheus")
                            .permitAll();
                    if (props.isRequireAudioRecordRole()) {
                        ex.anyExchange().hasAuthority(props.getAudioRecordRole());
                    } else {
                        ex.anyExchange().authenticated();
                    }
                })
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Reactive decoder preserving the dual-URI contract (jwk-set-uri = internal keys,
     * issuer-uri = iss validation) + layering the audience validator on top of the
     * default issuer/timestamp validators.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        final NimbusReactiveJwtDecoder decoder = (jwkSetUri != null && !jwkSetUri.isBlank())
                ? NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();
        final OAuth2TokenValidator<Jwt> withAudience = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new AudienceValidator(props.isEnforceAudience(), props.getResourceClientId()));
        decoder.setJwtValidator(withAudience);
        return decoder;
    }

    /**
     * Maps token scopes + {@code resource_access.<resourceClientId>.roles} (e.g.
     * {@code audio_record}) into Spring authorities. Role extraction is always applied
     * (harmless when absent); whether the role is REQUIRED is the
     * {@code requireAudioRecordRole} flag in the filter chain above.
     */
    Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        final String clientId = props.getResourceClientId();
        return jwt -> {
            final Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            final Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
            if (resourceAccess != null && resourceAccess.get(clientId) instanceof Map<?, ?> client
                    && client.get("roles") instanceof Collection<?> roles) {
                for (final Object role : roles) {
                    authorities.add(new SimpleGrantedAuthority(String.valueOf(role)));
                }
            }
            return Mono.just(new JwtAuthenticationToken(jwt, authorities));
        };
    }
}
