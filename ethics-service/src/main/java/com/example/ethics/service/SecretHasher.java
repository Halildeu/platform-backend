package com.example.ethics.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SecretHasher {
    private final SecureRandom random = new SecureRandom();

    public String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String secret, int iterations) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return encode(iterations, salt, derive(secret, salt, iterations));
    }

    public boolean verify(String secret, String encoded) {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(secret, salt, iterations));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private byte[] derive(String secret, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", unavailable);
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(int iterations, byte[] salt, byte[] hash) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "pbkdf2-sha256$" + iterations + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }
}
