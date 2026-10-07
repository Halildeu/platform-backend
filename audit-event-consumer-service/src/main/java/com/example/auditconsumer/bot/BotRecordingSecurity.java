package com.example.auditconsumer.bot;

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** No user-token compatibility and no local/dev bypass on the bot authority. Disabled by default. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class BotRecordingSecurity {
    static final String PERMISSION = "audit:bot-recording:manage";
    static final String CLIENT = "meeting-service";
    static final String AUDIENCE = "audit-event-consumer-service";

    @Bean
    JwtDecoder botRecordingDecoder(Environment environment) {
        if (!environment.getProperty("audit.bot-recording.enabled", Boolean.class, false)) {
            return token -> { throw new BadJwtException("bot_recording_disabled"); };
        }
        String issuer = environment.getRequiredProperty("audit.bot-recording.service-issuer");
        String jwks = environment.getRequiredProperty("audit.bot-recording.service-jwks-uri");
        if (issuer.isBlank() || jwks.isBlank()) throw new IllegalArgumentException("bot recording trust configuration required");
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build(); // RS256 only; no token-supplied key URL.
        configure(decoder, issuer);
        return decoder;
    }

    static void configure(NimbusJwtDecoder decoder, String issuer) {
        var defaults = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(claims -> {
            // Spring's default claim converter synthesizes iat when absent. Require original signed timestamps.
            if (claims.get("iat") == null || claims.get("exp") == null) throw new BadJwtException("signed timestamps required");
            return defaults.convert(claims);
        });
        decoder.setJwtValidator(validator(issuer));
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), jwt -> {
            Object permissions = jwt.getClaims().get("perm");
            boolean valid = jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE) && CLIENT.equals(jwt.getSubject())
                    && CLIENT.equals(jwt.getClaims().get("client_id"))
                    && permissions instanceof List<?> list && list.contains(PERMISSION);
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "bot recording service identity required", null));
        });
    }

    @Bean
    SecurityFilterChain botRecordingSecurityFilterChain(HttpSecurity http, JwtDecoder botRecordingDecoder) throws Exception {
        var converter = new JwtAuthenticationConverter();
        // Only this strict decoder can issue this authority; roles, scope and azp are deliberately ignored.
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("SVC_" + PERMISSION)));
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(EndpointRequest.to("health", "info", "metrics", "prometheus")).permitAll()
                        .requestMatchers("/api/v1/internal/bot-recording/**").hasAuthority("SVC_" + PERMISSION)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(botRecordingDecoder).jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
