package com.example.user.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.user.config.JwtProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() throws Exception {
        RSAKey rsaKey = generateRsaKey();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(jwkSource);
        JwtDecoder jwtDecoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();

        JwtProperties properties = new JwtProperties();
        properties.setIssuer("auth-service");
        properties.setExpiration(3_600_000L);

        jwtTokenProvider = new JwtTokenProvider(properties, jwtEncoder, jwtDecoder);
    }

    @Test
    void shouldValidateAndParseRs256Token() {
        UserDetails userDetails = User.withUsername("test@example.com")
                .password("password")
                .roles("ADMIN")
                .build();

        String token = jwtTokenProvider.generateToken(userDetails);

        assertNotNull(token);
        assertTrue(jwtTokenProvider.validateToken(token));
        assertEquals("test@example.com", jwtTokenProvider.getUsernameFromToken(token));
    }

    @Test
    void shouldRejectTamperedTokens() {
        UserDetails userDetails = User.withUsername("tamper@example.com")
                .password("password")
                .roles("USER")
                .build();

        String token = jwtTokenProvider.generateToken(userDetails);
        String tampered = token.substring(0, token.length() - 2) + "zz";

        assertFalse(jwtTokenProvider.validateToken(tampered));
    }

    private RSAKey generateRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("test-key").build();
    }
}
