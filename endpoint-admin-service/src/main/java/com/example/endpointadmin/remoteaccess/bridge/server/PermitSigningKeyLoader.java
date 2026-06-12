package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Faz 22.6 T-4a-ii (Codex 019ebc7e) — loads the broker's permit-signing key fail-closed at startup,
 * mirroring the T-2c TLS key-load pattern ({@link RemoteBridgeGrpcServer#buildServerCredentials}): an enabled
 * bridge that cannot sign permits MUST NOT serve. There is NO insecure escape for this key (unlike the
 * transport-TLS smoke flag) — a signer is the floor of every operation authorization.
 *
 * <p>The curve/alg/kid final boundary is the {@link RemoteBridgePermitSigner} constructor (exact P-256
 * domain-param compare); this loader only gets a {@link PrivateKey} into it, refusing everything that isn't a
 * readable PKCS#8 ({@code BEGIN PRIVATE KEY}, NOT SEC1 {@code BEGIN EC PRIVATE KEY}) EC key, and wrapping
 * every failure in ONE config-shaped {@link IllegalStateException} that never logs the key body.
 */
public final class PermitSigningKeyLoader {

    private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----"; // gitleaks:allow (PEM armor literal, not a key)
    private static final String PKCS8_END = "-----END PRIVATE KEY-----";

    private PermitSigningKeyLoader() {
    }

    /** Build the signer from the configured permit key/kid, or fail closed (never returns null). */
    public static RemoteBridgePermitSigner load(RemoteBridgeServerProperties.Permit permit) {
        if (permit == null || permit.kid() == null || permit.kid().isBlank()) {
            throw new IllegalStateException(
                    "remote-bridge.permit.kid is required for an enabled broker — refusing to start");
        }
        String path = permit.signingKeyPemPath();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "remote-bridge.permit.signing-key-pem-path is required for an enabled broker — refusing to start");
        }
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) {
            throw new IllegalStateException("remote-bridge permit signing key file is missing or unreadable: "
                    + path + " — refusing to start");
        }
        try {
            PrivateKey key = parsePkcs8EcKey(Files.readString(file.toPath()));
            // the signer constructor is the FINAL P-256/alg/kid boundary (exact domain-param compare) —
            // folded into the same try so a non-P-256 key is ALSO ONE config-shaped failure (Codex P2)
            return new RemoteBridgePermitSigner(key, permit.kid(), RemoteBridgePermitSigner.PERMIT_ALG);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // ONE config-shaped failure — never echo the key body (path + kid are enough to fix it)
            throw new IllegalStateException("remote-bridge permit signing key failed to load/validate ("
                    + path + ", kid=" + permit.kid() + ") — refusing to start", e);
        }
    }

    /**
     * STRICT PKCS#8 parse (Codex P2): exactly ONE PEM block, anchored armor, and it MUST be
     * {@code BEGIN PRIVATE KEY}. Any extra {@code -----BEGIN }/{@code -----END } line (dual-armor, a
     * concatenated SEC1 block, an armor-in-comment) is refused — SEC1 {@code BEGIN EC PRIVATE KEY} too.
     */
    private static PrivateKey parsePkcs8EcKey(String pem) throws GeneralSecurityException {
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
