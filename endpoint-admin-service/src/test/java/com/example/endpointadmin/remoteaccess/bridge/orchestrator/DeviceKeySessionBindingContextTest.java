package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.6 #548 slice-1 step-5 — {@link DeviceKeySessionBindingContext}: deterministic, domain-separated, and
 * UNAMBIGUOUS (length-prefixed) so the agent (Go) and the broker (Java) compute byte-identical bytes and no two
 * distinct field tuples can collide.
 */
class DeviceKeySessionBindingContextTest {

    private static final byte[] NONCE = "broker-nonce-32-bytes-exactly!!!".getBytes(StandardCharsets.US_ASCII);

    @Test
    void deterministic_sameInputsSameBytes() {
        byte[] a = DeviceKeySessionBindingContext.compute("cid", NONCE, "peer", 1_000L);
        byte[] b = DeviceKeySessionBindingContext.compute("cid", NONCE, "peer", 1_000L);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void carriesDomainTagPrefix() {
        byte[] ctx = DeviceKeySessionBindingContext.compute("cid", NONCE, "peer", 1_000L);
        byte[] tag = DeviceKeySessionBindingContext.DOMAIN_TAG.getBytes(StandardCharsets.US_ASCII);
        assertThat(Arrays.copyOf(ctx, tag.length)).as("domain-separation tag leads the context").isEqualTo(tag);
    }

    @Test
    void anyFieldChange_changesBytes() {
        byte[] base = DeviceKeySessionBindingContext.compute("cid", NONCE, "peer", 1_000L);
        assertThat(DeviceKeySessionBindingContext.compute("CID", NONCE, "peer", 1_000L)).isNotEqualTo(base);
        byte[] otherNonce = NONCE.clone();
        otherNonce[0] ^= 0x01;
        assertThat(DeviceKeySessionBindingContext.compute("cid", otherNonce, "peer", 1_000L)).isNotEqualTo(base);
        assertThat(DeviceKeySessionBindingContext.compute("cid", NONCE, "peerX", 1_000L)).isNotEqualTo(base);
        assertThat(DeviceKeySessionBindingContext.compute("cid", NONCE, "peer", 1_001L)).isNotEqualTo(base);
    }

    @Test
    void lengthPrefixing_removesConcatenationAmbiguity() {
        // ("ab","cd"...) must NOT collide with ("a","bcd"...) — the UINT32 length prefixes disambiguate
        byte[] left = DeviceKeySessionBindingContext.compute("ab", "cd".getBytes(StandardCharsets.US_ASCII),
                "peer", 7L);
        byte[] right = DeviceKeySessionBindingContext.compute("a", "bcd".getBytes(StandardCharsets.US_ASCII),
                "peer", 7L);
        assertThat(left).isNotEqualTo(right);
    }

    @Test
    void rejectsMissingInputs() {
        assertThatThrownBy(() -> DeviceKeySessionBindingContext.compute(null, NONCE, "peer", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeviceKeySessionBindingContext.compute("cid", new byte[0], "peer", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeviceKeySessionBindingContext.compute("cid", NONCE, " ", 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
