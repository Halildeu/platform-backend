package com.example.endpointadmin.remoteaccess;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Faz 22.6 C-2 — an out-of-band SIGNED commitment to a {@link SessionRecordingChain}'s state at a point in
 * time (ADR-0034 D3). The recorder periodically signs {@code (chainId, count, headHash, timestamp)} and ships
 * the anchor to a SEPARATE sink. The C-1 chain proves INTERNAL integrity (no entry was altered/inserted/
 * removed/re-ordered); the anchor adds the COMPLETENESS proof the chain alone cannot give — a later
 * truncation (drop the tail) or wholesale replacement (re-mint a different chain) is caught because the
 * anchored {@code count}/{@code headHash} were signed out-of-band and are monotonic.
 *
 * <p>The signature covers everything EXCEPT itself, over a length-prefixed canonical with a domain tag.
 */
public record RecordingAnchor(String chainId, long count, String headHash, long timestampMillis,
                              String signature) {

    private static final String ANCHOR_DOMAIN = "RecordingAnchor:v1";

    /** The bytes the recorder signs (the anchor's fields, excluding the signature). */
    public static byte[] canonicalBytes(String chainId, long count, String headHash, long timestampMillis) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, ANCHOR_DOMAIN);
        writeField(out, chainId == null ? "" : chainId);
        writeField(out, Long.toString(count));
        writeField(out, headHash == null ? "" : headHash);
        writeField(out, Long.toString(timestampMillis));
        return out.toByteArray();
    }

    /** This anchor's signed bytes (for verification). */
    public byte[] canonicalBytes() {
        return canonicalBytes(chainId, count, headHash, timestampMillis);
    }

    private static void writeField(ByteArrayOutputStream out, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >>> 24) & 0xFF);
        out.write((bytes.length >>> 16) & 0xFF);
        out.write((bytes.length >>> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.writeBytes(bytes);
    }
}
