package com.example.endpointadmin.tpmattest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Faz 22.3B (ADR-0039) gate-4a-2.3 — TCG <b>KDFa</b> (TPM 2.0 Part 1 §11.4.10.2),
 * the SP800-108 counter-mode KDF with HMAC used by {@code TPM2_MakeCredential} to
 * derive the credential-protection symmetric key (STORAGE) and the outer-integrity
 * HMAC key (INTEGRITY) from the protection seed.
 *
 * <pre>
 * KDFa(hashAlg, key, label, contextU, contextV, bits):
 *   for i in 1.. until 'bytes' produced:
 *     block_i = HMAC(hashAlg, key, BE32(i) ‖ label ‖ 0x00 ‖ contextU ‖ contextV ‖ BE32(bits))
 *   K = (concat blocks)[0 .. bytes]   ; if bits % 8 != 0, mask the high bits of K[0]
 * </pre>
 * The single {@code 0x00} after the label is the TCG label terminator; pass the
 * label WITHOUT a trailing null.
 */
final class TpmKdfa {

    private TpmKdfa() {}

    static byte[] kdfa(String jcaHmac, byte[] key, String label, byte[] contextU, byte[] contextV, int bits) {
        if (bits <= 0) {
            throw new IllegalArgumentException("bits must be > 0");
        }
        int bytes = (bits + 7) / 8;
        byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
        byte[] cu = contextU == null ? new byte[0] : contextU;
        byte[] cv = contextV == null ? new byte[0] : contextV;
        try {
            Mac mac = Mac.getInstance(jcaHmac);
            mac.init(new SecretKeySpec(key, jcaHmac));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int counter = 0;
            while (out.size() < bytes) {
                counter++;
                mac.reset();
                mac.update(be32(counter));
                mac.update(labelBytes);
                mac.update((byte) 0x00);
                mac.update(cu);
                mac.update(cv);
                mac.update(be32(bits));
                out.writeBytes(mac.doFinal());
            }
            byte[] full = out.toByteArray();
            byte[] result = new byte[bytes];
            System.arraycopy(full, 0, result, 0, bytes);
            int rem = bits % 8;
            if (rem != 0) {
                // Non-byte-aligned size: zero the excess (high) bits of the FIRST octet, keeping
                // the low (bits % 8) bits. Byte-for-byte the TPM reference rule
                // (tpm2-tss CryptKDFa: keyStream[0] &= ((1 << (sizeInBits % 8)) - 1)).
                // The 128/256-bit uses here never trigger it; TpmKdfaTest covers bits=130.
                result[0] &= (byte) ((1 << rem) - 1);
            }
            return result;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("KDFa failed for " + jcaHmac, e);
        }
    }

    static byte[] be32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }
}
