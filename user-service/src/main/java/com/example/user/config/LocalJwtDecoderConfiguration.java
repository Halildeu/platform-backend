package com.example.user.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
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
            String audience = firstNonBlank(
                firstFromList(environment.getProperty("spring.security.oauth2.resourceserver.jwt.audiences")),
                environment.getProperty("security.jwt.audience"),
                environment.getProperty("spring.application.name"),
                "user-service"
            );

            NimbusJwtDecoder keycloakDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(java.util.List.of(audience))
            );
            keycloakDecoder.setJwtValidator(validator);

            NimbusJwtDecoder localDecoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();

            return token -> {
                try {
                    return keycloakDecoder.decode(token);
                } catch (Exception ignore) {
                    return localDecoder.decode(token);
                }
            };
        } catch (JOSEException ex) {
            throw new IllegalStateException("Local JWT public key parse edilemedi", ex);
        }
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
