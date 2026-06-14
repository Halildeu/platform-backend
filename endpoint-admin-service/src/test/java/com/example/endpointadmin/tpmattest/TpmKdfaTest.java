package com.example.endpointadmin.tpmattest;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 22.3B gate-4a-2.3 — TCG KDFa unit tests (Codex review): a known-answer test against a
 * directly-computed HMAC first block, and the non-byte-aligned bit-mask rule (bits=130) which
 * the 128/256-bit production uses never exercise.
 */
class TpmKdfaTest {

    private static byte[] testKey() {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 0xA5);
        return k;
    }

    @Test
    void kdfa_firstBlockEqualsDirectHmac() throws Exception {
        byte[] k = testKey();
        byte[] ctx = {1, 2, 3, 4};
        byte[] out = TpmKdfa.kdfa("HmacSHA256", k, "STORAGE", ctx, new byte[0], 128);

        // Direct: HMAC(k, BE32(1) ‖ "STORAGE" ‖ 0x00 ‖ ctx ‖ BE32(128))[0..16]
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(k, "HmacSHA256"));
        mac.update(TpmKdfa.be32(1));
        mac.update("STORAGE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        mac.update((byte) 0x00);
        mac.update(ctx);
        mac.update(TpmKdfa.be32(128));
        byte[] expected = Arrays.copyOf(mac.doFinal(), 16);

        assertThat(out).as("KDFa(STORAGE,128) == first 16 bytes of the direct counter-mode HMAC").isEqualTo(expected);
    }

    @Test
    void kdfa_spansMultipleBlocksDeterministically() {
        byte[] k = testKey();
        // 384 bits = 48 bytes > one SHA-256 (32B) block → exercises the counter loop.
        byte[] a = TpmKdfa.kdfa("HmacSHA256", k, "INTEGRITY", new byte[0], new byte[0], 384);
        byte[] b = TpmKdfa.kdfa("HmacSHA256", k, "INTEGRITY", new byte[0], new byte[0], 384);
        assertThat(a).hasSize(48).isEqualTo(b);
    }

    @Test
    void kdfa_nonByteAlignedBitsMaskHighBitsOfFirstOctet() {
        byte[] k = testKey();
        byte[] out = TpmKdfa.kdfa("HmacSHA256", k, "X", new byte[0], new byte[0], 130);
        assertThat(out).as("ceil(130/8)").hasSize(17);
        // tpm2-tss rule: keep the low (130 % 8 = 2) bits of octet 0; the high 6 are zeroed.
        assertThat(out[0] & 0xFC).as("top 6 bits of the first octet are masked to 0").isZero();
    }
}
