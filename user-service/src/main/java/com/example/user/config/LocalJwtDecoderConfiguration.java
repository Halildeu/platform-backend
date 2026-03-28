package com.example.user.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.example.user.security.AudienceOrAuthorizedPartyValidator;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import com.example.user.security.AudienceValidator;

/**
 * Local/dev profillerinde SecurityConfig devre dışı olduğu için
 * Spring'in varsayılan JwtDecoder beani üretilmez. Buna rağmen
 * legacy JwtTokenProvider testler için çalışmaya devam eder ve
 * JwtDecoder bağımlılığı ister. Burada aynı RSA anahtarından
 * basit bir decoder üreterek dependency zincirini tamamlıyoruz.
 */
@Configuration
@Profile({"local", "dev"})
public class LocalJwtDecoderConfiguration {

    private final Environment environment;

    public LocalJwtDecoderConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    public JwtDecoder localServiceJwtDecoder(RSAKey rsaKey) {
        try {
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
            String authServiceJwkSetUri = firstNonBlank(
                environment.getProperty("security.service-auth.jwk-set-uri"),
                environment.getProperty("SERVICE_AUTH_JWK_SET_URI"),
                "http://127.0.0.1:8088/oauth2/jwks"
            );
            String authServiceIssuer = firstNonBlank(
                environment.getProperty("security.service-auth.issuer"),
                environment.getProperty("SERVICE_AUTH_ISSUER"),
                "auth-service"
            );
            List<String> audiences = resolveAudiences();
            List<String> allowedClientIds = resolveAllowedClientIds();

            NimbusJwtDecoder keycloakDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceOrAuthorizedPartyValidator(audiences, allowedClientIds)
            );
            keycloakDecoder.setJwtValidator(validator);

            NimbusJwtDecoder authServiceFallbackDecoder = NimbusJwtDecoder.withJwkSetUri(authServiceJwkSetUri).build();
            authServiceFallbackDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(authServiceIssuer));

            NimbusJwtDecoder localDecoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();

            return token -> {
                try {
                    return keycloakDecoder.decode(token);
                } catch (Exception ignore) {
                    try {
                        return authServiceFallbackDecoder.decode(token);
                    } catch (Exception ignoredAgain) {
                        return localDecoder.decode(token);
                    }
                }
            };
        } catch (JOSEException ex) {
            throw new IllegalStateException("Local JWT public key parse edilemedi", ex);
        }
    }

    List<String> resolveAudiences() {
        String audienceProp = firstNonBlank(
            environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences"),
            environment.getProperty("security.service-auth.expected-audience"),
            environment.getProperty("security.jwt.audience"),
            environment.getProperty("spring.application.name"),
            "user-service"
        );
        return splitCsv(audienceProp);
    }

    List<String> resolveAllowedClientIds() {
        String raw = firstNonBlank(
            environment.getProperty("security.service-auth.allowed-client-ids"),
            environment.getProperty("SECURITY_AUTH_ALLOWED_CLIENT_IDS"),
            ""
        );
        return splitCsv(raw);
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(part -> !part.isBlank())
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
