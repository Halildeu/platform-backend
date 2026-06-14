package com.example.endpointadmin.tpmattest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Faz 22.3B gate-4a-2.3 — interop EMIT step (normally DISABLED). Driven by
 * {@code swtpm-makecredential-interop.sh}: given a real swtpm EK public + AK Name, it runs the
 * production {@link TpmMakeCredential} and writes a {@code tpm2_activatecredential}-compatible
 * credential file. The script then activates it on swtpm (which holds the EK private) and asserts
 * the recovered secret == the issued secret — the TPM-spec interop proof Codex required.
 *
 * <p>Enabled only with {@code -Dtpm.interop.emit=true}; absent in normal CI runs (no-op/skipped).
 */
class TpmMakeCredentialInteropEmitTest {

    @Test
    @EnabledIfSystemProperty(named = "tpm.interop.emit", matches = "true")
    void emitCredentialFileForSwtpmActivation() throws Exception {
        byte[] ekPub = Base64.getDecoder().decode(req("tpm.interop.ekpubB64"));
        byte[] akName = HexFormat.of().parseHex(req("tpm.interop.aknameHex"));
        byte[] secret = HexFormat.of().parseHex(req("tpm.interop.secretHex"));
        Path out = Path.of(req("tpm.interop.outFile"));

        TpmPublicArea ek = TpmPublicArea.parse(ekPub, true);
        TpmMakeCredential.Credential cred =
                new TpmMakeCredential().make(ek.toPublicKey(), ek.nameAlg(), akName, secret);
        Files.write(out, TpmMakeCredential.toToolsFile(cred));
        System.out.println("INTEROP_EMIT_OK bytes=" + Files.size(out) + " -> " + out);
    }

    private static String req(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("missing -D" + key);
        }
        return v.trim();
    }
}
