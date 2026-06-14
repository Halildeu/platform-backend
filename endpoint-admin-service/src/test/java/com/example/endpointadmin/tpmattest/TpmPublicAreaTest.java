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
    void unsupportedNameAlgRejected() {
        assertThatThrownBy(() -> TpmPublicArea.jcaHash(0x0004 /* SHA1 */))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
