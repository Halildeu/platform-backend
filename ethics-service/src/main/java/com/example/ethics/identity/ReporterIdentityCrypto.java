package com.example.ethics.identity;

import com.example.ethics.config.ReporterIdentityProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * ES-212 — AES-256-GCM for the reporter identity.
 *
 * <p>The case id is bound in as additional authenticated data. That is not decoration: it
 * means a ciphertext lifted from one row and pasted into another fails to decrypt rather
 * than quietly returning the wrong person's name. Without it, someone with write access to
 * the table could move an identity onto a different case and the read path would have no
 * way to notice.
 */
@Component
public class ReporterIdentityCrypto {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final ReporterIdentityProperties properties;
    private final SecureRandom random = new SecureRandom();

    public ReporterIdentityCrypto(ReporterIdentityProperties properties) {
        this.properties = properties;
    }

    /** Whether identity collection can operate at all — see {@link ReporterIdentityProperties}. */
    public boolean isOperational() {
        String active = properties.getActiveKeyId();
        return active != null && !active.isBlank() && keyMaterial(active) != null;
    }

    public String activeKeyId() {
        return properties.getActiveKeyId();
    }

    public Sealed seal(UUID caseId, String plaintext) {
        String keyId = properties.getActiveKeyId();
        byte[] key = requireKey(keyId);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(caseId));
            return new Sealed(keyId, nonce, cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // No detail escapes: an exception message that echoed the plaintext, or even its
            // length, would put the identity in a stack trace — which is exactly the place
            // this whole design keeps it out of.
            throw new IllegalStateException("reporter identity could not be sealed");
        }
    }

    public String open(UUID caseId, String keyId, byte[] nonce, byte[] ciphertext) {
        byte[] key = requireKey(keyId);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(caseId));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("reporter identity could not be opened");
        }
    }

    private static byte[] aad(UUID caseId) {
        return caseId.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] requireKey(String keyId) {
        byte[] key = keyMaterial(keyId);
        if (key == null) {
            throw new IllegalStateException("no key material for id");
        }
        return key;
    }

    private byte[] keyMaterial(String keyId) {
        Map<String, String> keys = properties.getKeys();
        if (keyId == null || keys == null) {
            return null;
        }
        String encoded = keys.get(keyId);
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        // A short key would still "work" in the sense that AES accepts 128- and 192-bit
        // material; refusing anything but 256 keeps a truncated or half-pasted secret from
        // silently downgrading every identity written under it.
        return key.length == 32 ? key : null;
    }

    public record Sealed(String keyId, byte[] nonce, byte[] ciphertext) {}
}
