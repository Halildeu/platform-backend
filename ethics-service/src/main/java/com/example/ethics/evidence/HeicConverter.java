package com.example.ethics.evidence;

/**
 * ES-104K dilim 2 (#2930) — HEIC decode assistance.
 *
 * <p>HEIC (ISO-BMFF + HEVC) has no pure-Java decoder, and linking libheif into this JVM
 * would put a large native attack surface next to request handling. So decode lives in a
 * separate, digest-pinned, single-purpose converter (the clamav discipline) and this
 * interface is the whole contract: HEIC bytes in, PNG bytes out, bounded. The converter's
 * output is never trusted as a derivative — the caller re-encodes it in-JVM.
 */
public interface HeicConverter {
    byte[] toPng(byte[] heic);
}
