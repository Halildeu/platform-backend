package com.example.user.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

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

    @Bean
    public JwtDecoder localServiceJwtDecoder(RSAKey rsaKey) {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Local JWT public key parse edilemedi", ex);
        }
    }
}
