package com.example.kcsmsotp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Random;

/**
 * Pure code-lifecycle logic over the auth-session note map: issue, resend,
 * verify with expiry and attempt ceilings. Keycloak-free on purpose so the
 * whole state machine is unit-testable without a server runtime.
 *
 * <p>The plaintext code never touches the notes — only SHA-256(salt || code)
 * and the salt are stored, and comparison goes through
 * {@link MessageDigest#isEqual} (constant-time).
 */
public final class SmsOtpCodeStore {

    /** Auth-session note keys (namespaced to avoid colliding with built-ins). */
    static final String NOTE_HASH = "SMS_OTP_HASH";
    static final String NOTE_SALT = "SMS_OTP_SALT";
    static final String NOTE_EXPIRY = "SMS_OTP_EXPIRY";
    static final String NOTE_ATTEMPTS = "SMS_OTP_ATTEMPTS";
    static final String NOTE_RESENDS = "SMS_OTP_RESENDS";

    /** Minimal view of {@code AuthenticationSessionModel} auth notes. */
    public interface Notes {
        String get(String key);

        void set(String key, String value);
    }

    public enum Status { OK, INVALID, EXPIRED, TOO_MANY_ATTEMPTS }

    public record VerifyResult(Status status, int remainingAttempts) {}

    private final Random rng;
    private final Clock clock;
    private final int ttlSeconds;
    private final int maxAttempts;
    private final int maxResends;

    public SmsOtpCodeStore(Random rng, Clock clock, int ttlSeconds, int maxAttempts, int maxResends) {
        this.rng = rng;
        this.clock = clock;
        this.ttlSeconds = ttlSeconds;
        this.maxAttempts = maxAttempts;
        this.maxResends = maxResends;
    }

    /** Generate a fresh 6-digit code, store salt+hash+expiry, reset attempts. */
    public String issue(Notes notes) {
        String code = String.format("%06d", rng.nextInt(1_000_000));
        byte[] saltBytes = new byte[16];
        rng.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);

        notes.set(NOTE_SALT, salt);
        notes.set(NOTE_HASH, hash(salt, code));
        notes.set(NOTE_EXPIRY, Long.toString(clock.millis() + ttlSeconds * 1000L));
        notes.set(NOTE_ATTEMPTS, "0");
        return code;
    }

    public boolean canResend(Notes notes) {
        return resendCount(notes) < maxResends;
    }

    /** Count a resend and issue a fresh code (the previous hash is replaced). */
    public String resend(Notes notes) {
        if (!canResend(notes)) {
            throw new IllegalStateException("resend ceiling reached");
        }
        notes.set(NOTE_RESENDS, Integer.toString(resendCount(notes) + 1));
        return issue(notes);
    }

    public int resendCount(Notes notes) {
        return intNote(notes, NOTE_RESENDS);
    }

    public VerifyResult verify(Notes notes, String input) {
        int attempts = intNote(notes, NOTE_ATTEMPTS);
        if (attempts >= maxAttempts) {
            return new VerifyResult(Status.TOO_MANY_ATTEMPTS, 0);
        }

        String salt = notes.get(NOTE_SALT);
        String expected = notes.get(NOTE_HASH);
        String expiryRaw = notes.get(NOTE_EXPIRY);
        if (salt == null || expected == null || expiryRaw == null) {
            // Nothing issued in this session — treat as expired so the user is
            // pushed to "resend" rather than burning verify attempts.
            return new VerifyResult(Status.EXPIRED, maxAttempts - attempts);
        }
        if (clock.millis() > Long.parseLong(expiryRaw)) {
            return new VerifyResult(Status.EXPIRED, maxAttempts - attempts);
        }

        attempts++;
        notes.set(NOTE_ATTEMPTS, Integer.toString(attempts));

        boolean match = input != null
                && MessageDigest.isEqual(
                        hash(salt, input.trim()).getBytes(StandardCharsets.US_ASCII),
                        expected.getBytes(StandardCharsets.US_ASCII));
        if (match) {
            // Single-use: a verified hash must not be replayable within the session.
            notes.set(NOTE_HASH, "");
            notes.set(NOTE_EXPIRY, "0");
            return new VerifyResult(Status.OK, maxAttempts - attempts);
        }
        if (attempts >= maxAttempts) {
            return new VerifyResult(Status.TOO_MANY_ATTEMPTS, 0);
        }
        return new VerifyResult(Status.INVALID, maxAttempts - attempts);
    }

    private static int intNote(Notes notes, String key) {
        String raw = notes.get(key);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.US_ASCII));
            digest.update(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
