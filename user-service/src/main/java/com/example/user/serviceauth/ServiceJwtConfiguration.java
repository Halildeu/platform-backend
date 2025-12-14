package com.example.user.serviceauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyFactory;
import java.security.KeyPair;
  import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

@Configuration
public class ServiceJwtConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ServiceJwtConfiguration.class);

    @Bean
    public RSAKey serviceRsaKey(ServiceJwtKeyProperties properties) {
        RSAPrivateKey privateKey;
        RSAPublicKey publicKey;

        if (!StringUtils.hasText(properties.getPrivateKey()) || !StringUtils.hasText(properties.getPublicKey())) {
            logger.warn("Service JWT anahtarları tanımlanmadı, geçici anahtar çifti üretilecek (yalnızca geliştirme amaçlı)");
            KeyPair keyPair = generateKeyPair();
            privateKey = (RSAPrivateKey) keyPair.getPrivate();
            publicKey = (RSAPublicKey) keyPair.getPublic();
        } else {
            privateKey = parsePrivateKey(properties.getPrivateKey());
            publicKey = parsePublicKey(properties.getPublicKey());
        }

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(properties.getKeyId())
                .build();
    }

    @Bean
    public JwtEncoder serviceJwtEncoder(RSAKey rsaKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    private RSAPrivateKey parsePrivateKey(String pem) {
        try {
            byte[] keyBytes = decodePem(pem, "PRIVATE KEY");
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
        } catch (Exception ex) {
            throw new IllegalStateException("RSA private key parse edilemedi", ex);
        }
    }

    private RSAPublicKey parsePublicKey(String pem) {
        try {
            byte[] keyBytes = decodePem(pem, "PUBLIC KEY");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) keyFactory.generatePublic(keySpec);
        } catch (Exception ex) {
            throw new IllegalStateException("RSA public key parse edilemedi", ex);
        }
    }

    private byte[] decodePem(String pem, String type) {
        String normalized = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("RSA anahtar çifti üretilemedi", ex);
        }
    }
}
