package com.example.user.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    public RSAKey testJwtRsaKey() throws Exception {
        return new RSAKeyGenerator(2048)
                .keyID("test-key")
                .generate();
    }

    @Bean
    @Primary
    public JwtEncoder testJwtEncoder(RSAKey testJwtRsaKey) {
        JWKSet jwkSet = new JWKSet(testJwtRsaKey);
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(jwkSet));
    }

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder(RSAKey testJwtRsaKey) throws Exception {
        return NimbusJwtDecoder.withPublicKey(testJwtRsaKey.toRSAPublicKey()).build();
    }
}
