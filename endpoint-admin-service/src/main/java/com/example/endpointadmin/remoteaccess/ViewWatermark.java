package com.example.endpointadmin.remoteaccess;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Faz 22.6 D-5 — the VIEW_ONLY session watermark spec (ADR-0033 §7, ADR-0034 D8; KVKK m.5/m.12). The pilot's
 * other capability is VIEW_ONLY screen-share; beyond {@link RemoteOperationGuard} (which only permits the
 * SCREEN_VIEW operation) it had no content control. This generates the deterministic watermark the C/D
 * transport renders over the viewed stream — an anti-exfil deterrent and a forensic attribution: a photographed
 * or screenshotted frame traces back to the exact operator + session via the audit log. Pure, total,
 * fail-closed, zero ripple (the transport renders the spec; this slice only computes it).
 *
 * <p><b>KVKK-privacy-aware split:</b> the VISIBLE label does NOT burn the operator's email/UPN into a frame
 * (that frame could itself leak, exposing the operator's PII). It shows a short, non-PII, <b>keyed</b>
 * fingerprint ({@code "RA-" + 10 hex of the HMAC}) plus a coarse UTC minute; the audit record stores the full
 * mapping (fingerprint → operatorId + sessionId + exact time), so a leaked frame is attributable via the log
 * WITHOUT the watermark itself exposing identity.
 *
 * <p><b>Keyed, not a bare hash (Codex 019eb874):</b> both the audit fingerprint and the visible fingerprint are
 * {@code HMAC-SHA-256(recorderKey, …)} truncations — an attacker with a candidate operator list cannot
 * cross-check a leaked fingerprint without the key, and a forged watermark cannot be computed. The recorder key
 * is provided at construction by the C/D driver (exactly like the C-2 {@code RecordingAnchorSigner} key); a
 * null/empty key is a wiring bug and fails fast at construction.
 *
 * <p><b>Fingerprint lengths (frozen for version compatibility):</b> audit = {@value #AUDIT_FP_HEX_LEN} hex
 * (128-bit truncated HMAC), visible = {@value #VISIBLE_FP_HEX_LEN} hex, canonicalSpecFingerprint = full
 * SHA-256 (64 hex) over the length-prefixed {@code version|keyId|visibleLabel|auditFingerprint|epochMillis|
 * bannerText|layout|opacity|fontScale|tileSpacing}.
 *
 * <p><b>Fail-closed/total:</b> a blank operatorId or sessionId still produces a spec (the view must ALWAYS be
 * watermarked — a missing watermark is worse than a degraded one) marked UNATTRIBUTED, its fingerprint still
 * distinguished by sessionId/time; it never throws.
 */
public final class ViewWatermark {

    /** Spec schema version — bump if a field's meaning or a fingerprint length changes. */
    public static final int VERSION = 1;
    static final int AUDIT_FP_HEX_LEN = 32;     // 128-bit truncated HMAC, logged
    static final int VISIBLE_FP_HEX_LEN = 10;   // shown in the frame, non-PII, keyed
    private static final String BANNER = "CONFIDENTIAL - Remote Support Session (KVKK)";
    private static final String DOMAIN = "ViewWatermark:v1";

    private static final DateTimeFormatter MINUTE_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /** The transport-render spec (abstract values; the C/D transport maps them to pixels). */
    public record RenderSpec(String layout, double opacity, double fontScale, int tileSpacing) {}

    /** The default render: a faint, repeated diagonal tiling that cannot be cropped out of a frame. */
    public static final RenderSpec DEFAULT_RENDER = new RenderSpec("TILED_DIAGONAL", 0.15, 1.0, 240);

    /** The complete, transport-renderable watermark spec for one session. Every field is populated.
     *  {@code sessionStartEpochMillis} is the STABLE session anchor (not a per-frame clock) so the
     *  {@code auditFingerprint} is constant for the whole session and the single per-session audit record can
     *  reproduce/map it. */
    public record WatermarkSpec(int version,
                                String keyId,
                                boolean attributed,
                                String visibleLabel,
                                String auditFingerprint,
                                long sessionStartEpochMillis,
                                String bannerText,
                                RenderSpec render,
                                String canonicalSpecFingerprint) {}

    private final byte[] recorderKey;
    private final String keyId;
    private final RenderSpec render;

    public ViewWatermark(byte[] recorderKey, String keyId) {
        this(recorderKey, keyId, DEFAULT_RENDER);
    }

    public ViewWatermark(byte[] recorderKey, String keyId, RenderSpec render) {
        if (recorderKey == null || recorderKey.length == 0) {
            throw new IllegalArgumentException("recorderKey must be present (the C/D driver provides it)");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must be present");
        }
        if (render == null) {
            throw new IllegalArgumentException("render must be present");
        }
        this.recorderKey = recorderKey.clone();
        this.keyId = keyId;
        this.render = render;
    }

    /**
     * Build the watermark spec for one session. Deterministic — same inputs yield the same spec.
     * {@code sessionStartEpochMillis} is the STABLE session anchor (the session's start, NOT a per-frame
     * clock) so the {@code auditFingerprint} is constant for the whole session and a leaked frame maps via the
     * single per-session audit record; passing a per-frame {@code now()} would make every frame's fingerprint
     * unmappable. No system clock is read here (determinism + the codebase forbids argless clocks). Total,
     * never throws: a blank operatorId/sessionId degrades to an UNATTRIBUTED-but-still-distinct spec.
     */
    public WatermarkSpec specFor(String operatorId, String sessionId, long sessionStartEpochMillis) {
        String op = operatorId == null ? "" : operatorId.trim();
        String sid = sessionId == null ? "" : sessionId.trim();
        boolean attributed = !op.isEmpty() && !sid.isEmpty();
        String marker = attributed ? "ATTRIBUTED" : "UNATTRIBUTED";

        // keyed fingerprint over a length-prefixed canonical (delimiter-safe: op|sid can't collide via a|b vs ab|);
        // includes the STABLE session-start so the fingerprint is constant for the session, not per-frame.
        String mac = hmacHex(lengthPrefixed(DOMAIN, marker, op, sid, Long.toString(sessionStartEpochMillis)));
        String auditFingerprint = mac.substring(0, AUDIT_FP_HEX_LEN);
        String coarse = MINUTE_UTC.format(
                Instant.ofEpochMilli(sessionStartEpochMillis).truncatedTo(ChronoUnit.MINUTES));
        String visiblePrefix = attributed ? "RA-" : "RA-UNATTR-";
        String visibleLabel = visiblePrefix + mac.substring(0, VISIBLE_FP_HEX_LEN) + " " + coarse;

        String canonicalSpecFingerprint = sha256Hex(lengthPrefixed(
                Integer.toString(VERSION), keyId, visibleLabel, auditFingerprint,
                Long.toString(sessionStartEpochMillis), BANNER, render.layout(), Double.toString(render.opacity()),
                Double.toString(render.fontScale()), Integer.toString(render.tileSpacing())));

        return new WatermarkSpec(VERSION, keyId, attributed, visibleLabel, auditFingerprint,
                sessionStartEpochMillis, BANNER, render, canonicalSpecFingerprint);
    }

    private String hmacHex(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(recorderKey, "HmacSHA256"));
            return toHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e); // never on a standard JRE
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 4-byte big-endian length + UTF-8 bytes per field — delimiter-safe canonicalisation. */
    private static byte[] lengthPrefixed(String... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never throws
        }
        return out.toByteArray();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // defensive: never expose the key
    @Override
    public String toString() {
        return "ViewWatermark[keyId=" + keyId + ", render=" + render + "]";
    }

    // (the key array is cloned in; no accessor exposes it)
    @SuppressWarnings("unused")
    private byte[] keyView() {
        return Arrays.copyOf(recorderKey, recorderKey.length);
    }
}
