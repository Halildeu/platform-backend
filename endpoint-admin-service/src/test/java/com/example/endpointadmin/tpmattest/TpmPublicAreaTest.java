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
        TpmPublicArea ak = TpmPublicArea.parse(akPub);

        // The decisive ground-truth assertion: our computed Name == the TPM's ak.name.
        assertThat(ak.computeNameHex())
                .as("recomputed AK Name equals the TPM-emitted ak.name")
                .isEqualTo(golden.get("akNameHex").asText());
    }

    @Test
    void v11_akIsRestrictedSigningKey() {
        byte[] akPub = Base64.getDecoder().decode(golden.get("akPub").asText());
        TpmPublicArea ak = TpmPublicArea.parse(akPub);

        // tpm2_createak produces a restricted signing key (RSA, SHA-256 name).
        assertThat(ak.type()).isEqualTo(TpmPublicArea.ALG_RSA);
        assertThat(ak.nameAlg()).isEqualTo(TpmPublicArea.ALG_SHA256);
        assertThat(ak.isRestrictedSigningKey())
                .as("AK is restricted + sign + fixedTPM + sensitiveDataOrigin (V11)").isTrue();
        assertThat(ak.isRestricted()).isTrue();
        assertThat(ak.isSign()).isTrue();
        assertThat(ak.isFixedTpm()).isTrue();
        assertThat(ak.isSensitiveDataOrigin()).isTrue();
    }

    @Test
    void v11_devkeyIsNotRestricted() {
        // The certified device key is an ordinary (unrestricted) key — contrast with the AK.
        byte[] devkey = Base64.getDecoder().decode(golden.get("devkeyPub").asText());
        TpmPublicArea dk = TpmPublicArea.parse(devkey);
        assertThat(dk.isRestricted()).as("device key is NOT a restricted key").isFalse();
    }

    @Test
    void unsupportedNameAlgRejected() {
        assertThatThrownBy(() -> TpmPublicArea.jcaHash(0x0004 /* SHA1 */))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
