package com.example.endpointadmin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds {@code idempotency_key} values that are guaranteed by code — not by
 * hand-computed comments — to fit their {@code VARCHAR(128)} column.
 *
 * <p>Board: platform-backend#921. Every command family assembles its key as
 * {@code <prefix><fixed scope segments><body>}. The budget left for the body
 * shrinks as the prefix grows, and until now that budget lived only in review
 * comments (see the {@code admin-uninstall} note: "Fixed prefix = 90 chars;
 * body MUST fit 38 chars"). {@code admin-update-agent:} shipped with a 93-char
 * fixed part, leaving a 35-char budget, while the generated fallback body is a
 * 36-char {@link java.util.UUID} — 129 characters into a 128-char column. The
 * frontend deliberately posts only {@code {releaseId, reason}} (no caller key),
 * so <em>every</em> UI-driven agent update failed with HTTP 500.
 *
 * <p>The fix is structural: {@link #build} keeps each call site's own caller-key
 * cap (that cap mirrors the DTO {@code @Size} contract) and then applies a final
 * column-bound guard. When the assembled key already fits, it is returned byte
 * for byte, so keys minted before this change still match on idempotent replay;
 * only the shapes that could not have been persisted at all are rewritten.
 */
public final class CommandIdempotencyKeys {

    /**
     * Mirrors {@code endpoint_commands.idempotency_key} and
     * {@code endpoint_uninstall_requests.idempotency_key}. Kept in sync with the
     * JPA mapping by {@code CommandIdempotencyKeysTest#columnLengthMatchesConstant}.
     */
    public static final int MAX_LENGTH = 128;

    /** Width of {@link #sha256Prefix(String)} output (8 bytes rendered as hex). */
    public static final int HASH_LENGTH = 16;

    private CommandIdempotencyKeys() {
    }

    /**
     * Assembles {@code fixedPart + body} so the result never exceeds
     * {@link #MAX_LENGTH}.
     *
     * @param fixedPart    discriminator plus fixed-width scope segments, already
     *                     terminated by its separator, e.g.
     *                     {@code "admin-update-agent:<deviceId>:<releaseId>:"}
     * @param requestedKey caller-supplied key, or {@code null} to mint one
     * @param callerKeyCap longest caller key accepted verbatim; longer keys are
     *                     collapsed to a {@link #HASH_LENGTH}-char digest. This
     *                     mirrors the request DTO's {@code @Size} contract and is
     *                     retained per call site so existing keys stay stable.
     * @throws IllegalStateException if {@code fixedPart} is so long that not even
     *                               a digest body fits — a wiring bug that must
     *                               fail at the call site rather than as a
     *                               runtime {@code 500} from PostgreSQL.
     */
    public static String build(String fixedPart, String requestedKey, int callerKeyCap) {
        if (fixedPart.length() + HASH_LENGTH > MAX_LENGTH) {
            throw new IllegalStateException(
                    "idempotency key prefix leaves no room for a body: '" + fixedPart
                            + "' is " + fixedPart.length() + " chars, which exceeds "
                            + (MAX_LENGTH - HASH_LENGTH));
        }

        String body = trimToNull(requestedKey);
        if (body == null) {
            body = java.util.UUID.randomUUID().toString();
        } else if (body.length() > callerKeyCap) {
            body = sha256Prefix(body);
        }

        String assembled = fixedPart + body;
        if (assembled.length() <= MAX_LENGTH) {
            return assembled;
        }
        // The body cannot fit alongside this prefix. Collapse it deterministically:
        // identical inputs keep producing identical keys, so idempotent replay of a
        // caller-supplied key still works.
        return fixedPart + sha256Prefix(assembled);
    }

    /**
     * Bounds a key that carries no fixed part of its own (the generic
     * {@code admin:} path accepts a caller key verbatim).
     */
    public static String bound(String key) {
        return key.length() <= MAX_LENGTH ? key : sha256Prefix(key);
    }

    static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HASH_LENGTH);
            for (int i = 0; i < HASH_LENGTH / 2; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
