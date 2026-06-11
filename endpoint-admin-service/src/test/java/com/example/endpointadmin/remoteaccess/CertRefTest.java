package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Faz 22.6 B1.4a-2 — {@link CertRef} identity-based equality (Codex 019eb6d9: chain excluded). */
class CertRefTest {

    @Test
    void equalityIsIdentityBasedNotChainArrayReference() {
        // same cert identity, DIFFERENT byte[] chain instances → must compare equal (record auto-equals
        // would have compared the List<byte[]> by reference and reported unequal).
        CertRef a = new CertRef("tp", "SHA-256", "serial", "CN=CA",
                List.of(new byte[] {1, 2, 3}, new byte[] {4, 5}));
        CertRef b = new CertRef("tp", "SHA-256", "serial", "CN=CA",
                List.of(new byte[] {1, 2, 3}, new byte[] {4, 5}));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentIdentityIsNotEqual() {
        CertRef a = new CertRef("tpA", "SHA-256", null, null);
        CertRef b = new CertRef("tpB", "SHA-256", null, null);
        assertNotEquals(a, b);
    }

    @Test
    void chainIsDefensivelyCopiedInAndOut() {
        byte[] src = {9, 9};
        CertRef ref = new CertRef("tp", "SHA-256", null, null, List.of(src));
        src[0] = 0; // mutate the SOURCE after construction — the stored chain must be unaffected
        assertEquals(9, ref.encodedChain().get(0)[0]);
        ref.encodedChain().get(0)[0] = 0; // mutate the RETURNED copy — the stored chain must be unaffected
        assertEquals(9, ref.encodedChain().get(0)[0]);
    }

    @Test
    void backCompatFourArgConstructorHasAnEmptyChain() {
        CertRef ref = new CertRef("tp", "SHA-256", "s", "i");
        assertEquals(0, ref.encodedChain().size());
    }
}
