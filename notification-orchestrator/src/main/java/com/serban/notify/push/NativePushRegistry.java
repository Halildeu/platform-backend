package com.serban.notify.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "notify.native-push.registry-enabled", havingValue = "true")
public class NativePushRegistry {
    private final JdbcTemplate jdbc;
    private final NativePushTokenCipher cipher;
    private final NativePushScopePolicy scopePolicy;

    public NativePushRegistry(JdbcTemplate jdbc, @Value("${notify.native-push.encryption-key}") String key,
                              NativePushScopePolicy scopePolicy) {
        this.jdbc = jdbc;
        this.cipher = new NativePushTokenCipher(Base64.getDecoder().decode(key));
        this.scopePolicy = scopePolicy;
    }

    @Transactional
    public UUID register(String org, String subscriber, UUID installation, String app,
                         String provider, String environment, String token) {
        scopePolicy.requireAllowed(app, provider, environment);
        // Stable ID survives token rotation; ownership is never reassigned by an upsert.
        String scope = app + "/" + provider + "/" + environment + "/" + installation;
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope);
        var existing = jdbc.query("""
            SELECT registration_id, org_id, subscriber_id FROM notify.native_push_registration
            WHERE application_id=? AND provider=? AND environment=? AND installation_id=?
            """, (rs, row) -> new Owner(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            app, provider, environment, installation);
        UUID id = UUID.randomUUID();
        if (!existing.isEmpty()) {
            Owner owner = existing.getFirst();
            if (!owner.org().equals(org) || !owner.subscriber().equals(subscriber)) throw conflict();
            id = owner.id();
        }
        String encrypted = cipher.encrypt(token, id.toString());
        try {
            jdbc.update("""
                INSERT INTO notify.native_push_registration
                (registration_id, org_id, subscriber_id, installation_id, application_id, provider,
                 environment, token_hash, token_ciphertext)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (registration_id) DO UPDATE SET
                  token_hash=excluded.token_hash, token_ciphertext=excluded.token_ciphertext, updated_at=now()
                """, id, org, subscriber, installation, app, provider, environment, hash(token), encrypted);
        } catch (DataIntegrityViolationException failure) {
            // Do not expose database exception text, which can contain the token fingerprint.
            throw conflict();
        }
        return id;
    }

    @Transactional
    public void remove(String org, String subscriber, UUID id) {
        jdbc.update("DELETE FROM notify.native_push_registration WHERE registration_id=? AND org_id=? AND subscriber_id=?",
            id, org, subscriber);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "NATIVE_PUSH_REGISTRATION_CONFLICT");
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private record Owner(UUID id, String org, String subscriber) {}
}
