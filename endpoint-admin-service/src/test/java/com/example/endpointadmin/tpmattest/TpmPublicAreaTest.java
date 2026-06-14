package com.example.endpointadmin.tpmattest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.3B gate-4a-2.1 — V11 (AK restricted-signing-key + AK-name recompute),
 * validated against the GROUND-TRUTH swtpm golden vector. The decisive check:
 * {@link TpmPublicArea#computeNameHex()} on the real {@code ak.pub} MUST equal the
 * TPM-emitted {@code ak.name} ({@code akNameHex}) — if the hand-parse/name
 * algorithm were wrong, this fails. Proves the parser against real TPM output.
 */
class TpmPublicAreaTest {

    private static JsonNode golden;

    @BeforeAll
    static void load() throws Exception {
        try (var in = TpmPublicAreaTest.class.getResourceAsStream("/tpmattest/golden-rsa.json")) {
            golden = new ObjectMapper().readTree(in);
        }
    }

    @Test
    void v11_akNameRecomputeMatchesTpmEmittedName() {
        byte[] akPub = Base64.getDecoder().decode(golden.get("akPub").asText());
        TpmPublicArea ak = TpmPublicArea.parse(akPub, true /* TPM2B_PUBLIC from tpm2_createak -u */);

        // The decisive ground-truth assertion: our computed Name == the TPM's ak.name.
        assertThat(ak.computeNameHex())
                .as("recomputed AK Name equals the TPM-emitted ak.name")
                .isEqualTo(golden.get("akNameHex").asText());
    }

    @Test
    void v11_akIsRestrictedSigningKey() {
        byte[] akPub = Base64.getDecoder().decode(golden.get("akPub").asText());
        TpmPublicArea ak = TpmPublicArea.parse(akPub, true);

        // tpm2_createak produces a restricted signing key (RSA, SHA-256 name).
        assertThat(ak.type()).isEqualTo(TpmPublicArea.ALG_RSA);
        assertThat(ak.nameAlg()).isEqualTo(TpmPublicArea.ALG_SHA256);
        assertThat(ak.isRestrictedSigningKey())
                .as("AK is restricted + sign + ¬decrypt + fixedTPM + fixedParent + sensitiveDataOrigin (V11)")
                .isTrue();
        assertThat(ak.isRestricted()).isTrue();
        assertThat(ak.isSign()).isTrue();
        assertThat(ak.isDecrypt()).as("a restricted SIGNING AK must NOT also decrypt").isFalse();
        assertThat(ak.isFixedTpm()).isTrue();
        assertThat(ak.isFixedParent()).as("AK is non-migratable (fixedParent)").isTrue();
        assertThat(ak.isSensitiveDataOrigin()).isTrue();
    }

    @Test
    void v11_devkeyIsNotRestricted() {
        // The certified device key is an ordinary (unrestricted) key — contrast with the AK.
        byte[] devkey = Base64.getDecoder().decode(golden.get("devkeyPub").asText());
        TpmPublicArea dk = TpmPublicArea.parse(devkey, true);
        assertThat(dk.isRestricted()).as("device key is NOT a restricted key").isFalse();
    }

    @Test
    void tpm2bSizeMismatchRejected() {
        // Explicit format contract: an isTpm2b input whose UINT16 size != remaining length is rejected
        // (no silent fallthrough to treating it as a bare TPMT_PUBLIC).
        byte[] akPub = Base64.getDecoder().decode(golden.get("akPub").asText());
        byte[] truncated = java.util.Arrays.copyOf(akPub, akPub.length - 1);
        assertThatThrownBy(() -> TpmPublicArea.parse(truncated, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size mismatch");
    }

    @Test
    void bareTpmtPublic_parsesIdenticallyWhenFlagSaysSo() {
        // Codex follow-up (gate-4a-2.2): cover the isTpm2b=false (bare TPMT_PUBLIC) path.
        byte[] tpm2b = Base64.getDecoder().decode(golden.get("akPub").asText());
        byte[] bare = java.util.Arrays.copyOfRange(tpm2b, 2, tpm2b.length); // strip the TPM2B size prefix
        TpmPublicArea fromBare = TpmPublicArea.parse(bare, false);
        TpmPublicArea fromWrapped = TpmPublicArea.parse(tpm2b, true);
        assertThat(fromBare.computeNameHex())
                .as("bare TPMT_PUBLIC (isTpm2b=false) parses identically to the TPM2B form")
                .isEqualTo(fromWrapped.computeNameHex());
    }

    @Test
    void wrappedBytesWithBareFlag_doNotSilentlyYieldTheTpmName() {
        // The explicit flag's whole point: claiming isTpm2b=false on TPM2B-wrapped bytes reads the
        // size prefix as type/nameAlg → it must NOT silently reproduce the correct TPM Name.
        byte[] tpm2b = Base64.getDecoder().decode(golden.get("akPub").asText());
        try {
            String wrong = TpmPublicArea.parse(tpm2b, false).computeNameHex();
            assertThat(wrong).isNotEqualTo(golden.get("akNameHex").asText());
        } catch (RuntimeException expected) {
            // also correct: the mis-typed nameAlg is rejected outright
        }
    }

    @Test
    void unsupportedNameAlgRejected() {
        assertThatThrownBy(() -> TpmPublicArea.jcaHash(0x0004 /* SHA1 */))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
