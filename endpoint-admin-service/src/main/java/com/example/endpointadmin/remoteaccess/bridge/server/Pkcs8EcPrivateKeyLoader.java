package com.example.endpointadmin.remoteaccess.bridge.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Faz 22.6 T-4a-ii slice-3c (Codex 019ebc7e) — the shared, strict PKCS#8 EC private-key loader. Both the
 * permit-signing key ({@link PermitSigningKeyLoader}, an online authorization artifact) and the recording-
 * anchor key (the durable-audit WORM-integrity artifact) parse their PEM the SAME way; the forensic-key
 * separation S2 demands is a SECRET / CONFIG / BEAN boundary (separate path, separate bean, separate rotation
 * domain), not a separate parser — so the parse logic lives here once, fail-closed.
 *
 * <p><b>STRICT (Codex P2):</b> exactly ONE PEM block, anchored armor, and it MUST be
 * {@code -----BEGIN PRIVATE KEY-----} (PKCS#8). Any extra {@code BEGIN}/{@code END} line (dual-armor, a
 * concatenated SEC1 block, an armor-in-comment) is refused; SEC1 {@code BEGIN EC PRIVATE KEY} too. The
 * curve/alg final boundary is the consumer (the signer constructor's exact P-256 compare) — this loader only
 * produces a {@link PrivateKey} from a readable PKCS#8 EC PEM and never echoes the key body.
 */
final class Pkcs8EcPrivateKeyLoader {

    static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"; // gitleaks:allow (PEM armor literal, not a key)
    static final String PKCS8_END = "-----END PRIVATE KEY-----";

    private Pkcs8EcPrivateKeyLoader() {
    }

    /**
     * Read + parse the PKCS#8 EC key at {@code path}, or fail closed. Every failure (missing/unreadable file,
     * malformed PEM, non-EC key) becomes ONE config-shaped {@link IllegalStateException} naming {@code
     * configKey} (so the operator can fix it) and NEVER logging the key body.
     *
     * @param path      filesystem path to the PEM
     * @param configKey the config property name to name in an error (e.g. {@code remote-bridge.recording.anchor-key.path})
     */
    static PrivateKey loadFromFile(String path, String configKey) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(configKey + " is required for an enabled broker — refusing to start");
        }
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) {
            throw new IllegalStateException(
                    configKey + " file is missing or unreadable: " + path + " — refusing to start");
        }
        try {
            return parsePkcs8EcKey(Files.readString(file.toPath()));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // ONE config-shaped failure — never echo the key body (path is enough to fix it)
            throw new IllegalStateException(
                    configKey + " failed to load/validate (" + path + ") — refusing to start", e);
        }
    }

    /** STRICT PKCS#8 EC parse — exactly one anchored {@code BEGIN PRIVATE KEY} block, SEC1/dual-armor refused. */
    static PrivateKey parsePkcs8EcKey(String pem) throws GeneralSecurityException {
        if (pem == null) {
            throw new IllegalArgumentException("empty PEM");
        }
        long beginCount = pem.lines().filter(l -> l.strip().startsWith("-----BEGIN ")).count();
        long endCount = pem.lines().filter(l -> l.strip().startsWith("-----END ")).count();
        if (beginCount != 1 || endCount != 1) {
            throw new IllegalArgumentException("expected exactly one PEM block, found " + beginCount
                    + " BEGIN / " + endCount + " END");
        }
        if (!pem.contains(PKCS8_BEGIN) || !pem.contains(PKCS8_END)) {
            throw new IllegalArgumentException("not a PKCS#8 PEM (expected BEGIN PRIVATE KEY, not SEC1)");
        }
        int start = pem.indexOf(PKCS8_BEGIN) + PKCS8_BEGIN.length();
        int end = pem.indexOf(PKCS8_END, start);
        String body = pem.substring(start, end).replaceAll("\\s", "");
        if (body.isEmpty()) {
            throw new IllegalArgumentException("empty PKCS#8 body");
        }
        byte[] der = Base64.getDecoder().decode(body); // throws IllegalArgumentException on bad base64
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
