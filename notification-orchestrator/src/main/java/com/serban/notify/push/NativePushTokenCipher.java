package com.serban.notify.push;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** Encrypts native provider tokens; caller supplies the immutable registration identity as AAD. */
public final class NativePushTokenCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public NativePushTokenCipher(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("Native push requires a 256-bit key");
        this.key = new SecretKeySpec(key.clone(), "AES");
    }

    public String encrypt(String token, String registrationIdentity) {
        if (token == null || token.isBlank() || token.length() > 4096) throw new IllegalArgumentException("Invalid native token");
        requireIdentity(registrationIdentity);
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, registrationIdentity);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Native token encryption failed");
        }
    }

    public String decrypt(String envelope, String registrationIdentity) {
        requireIdentity(registrationIdentity);
        try {
            if (envelope == null || envelope.length() > 22000) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(envelope);
            if (bytes.length < 29) throw new IllegalArgumentException();
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, registrationIdentity).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Never attach provider material or crypto exception contents to logs.
            throw new IllegalStateException("Native token authentication failed");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String identity) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static void requireIdentity(String identity) {
        if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Registration identity required");
    }
}
