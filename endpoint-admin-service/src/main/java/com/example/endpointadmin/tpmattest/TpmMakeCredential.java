package com.example.endpointadmin.tpmattest;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Faz 22.3B (ADR-0039) gate-4a-2.3 — server-side software {@code TPM2_MakeCredential}
 * (verifier <b>V10</b> credential-activation, design §4/§10.5), the cryptographic heart
 * of the one-TPM proof. <b>Disabled-by-default</b>; wired at gate-4d.
 *
 * <p>The server wraps a fresh single-use {@code secret} so that ONLY the TPM holding
 * both the presented EK (to OAEP-decrypt the protection seed) and the presented AK
 * (whose Name keys the KDFa + outer HMAC) can recover it via {@code TPM2_ActivateCredential}.
 * A successful activation therefore proves EK↔AK are in the same TPM — this IS verifier
 * <b>V3</b> (AK↔EK binding); there is no separate V3 wire check. The EK is V2-validated
 * and the AK Name is V11-validated before this runs.
 *
 * <pre>
 * MakeCredential(ekPub, ekNameAlg, akName, secret):
 *   seed       = random(digestSize(nameAlg))
 *   encSecret  = RSA-OAEP(ekPub, seed; hash=nameAlg, MGF1=nameAlg, label="IDENTITY\0")
 *   symKey     = KDFa(nameAlg, seed, "STORAGE",   contextU=akName, bits=128)   // AES-128
 *   encIdentity= AES-128-CFB(symKey, IV=0)  over  TPM2B(secret)
 *   hmacKey    = KDFa(nameAlg, seed, "INTEGRITY",                  bits=digestBits)
 *   outerHMAC  = HMAC(nameAlg, hmacKey, encIdentity ‖ akName)
 *   idObject   = TPM2B(outerHMAC) ‖ encIdentity
 *   → { credentialBlob = idObject, encSecret }
 * </pre>
 *
 * Validated against the swtpm golden (activate=MATCH) by a Java self-round-trip
 * ({@code TpmMakeCredentialTest}) and a real {@code tpm2_activatecredential} interop run.
 */
public final class TpmMakeCredential {

    /** AES-128 credential size — matches the golden vector's 16-byte secret. */
    public static final int SECRET_BYTES = 16;
    private static final byte[] OAEP_LABEL = label("IDENTITY"); // "IDENTITY\0"

    private final SecureRandom random;

    public TpmMakeCredential() { this(new SecureRandom()); }
    public TpmMakeCredential(SecureRandom random) { this.random = random; }

    /** The server-issued challenge: the secret to compare against + the blobs sent to the device. */
    public record Challenge(byte[] secret, byte[] credentialBlob, byte[] encSecret) {}
    /** The {@code TPM2_MakeCredential} output. */
    public record Credential(byte[] credentialBlob, byte[] encSecret) {}

    /** V10 issue: generate a fresh single-use secret and wrap it for (ekPub, akName). */
    public Challenge issueChallenge(PublicKey ekPub, int ekNameAlg, byte[] akName) {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        Credential c = make(ekPub, ekNameAlg, akName, secret);
        return new Challenge(secret, c.credentialBlob(), c.encSecret());
    }

    /**
     * V10 verify: the device-recovered secret must equal the issued secret. Constant-time,
     * fail-closed → {@link TpmDenyCode#ACTIVATION_FAILED}.
     */
    public static void verifyRecoveredSecret(byte[] issuedSecret, byte[] recoveredSecret) {
        if (issuedSecret == null || recoveredSecret == null
                || !MessageDigest.isEqual(issuedSecret, recoveredSecret)) {
            throw new TpmAttestException(TpmDenyCode.ACTIVATION_FAILED,
                    "recovered credential secret != issued secret (EK↔AK↔one-TPM proof failed)");
        }
    }

    public Credential make(PublicKey ekPub, int ekNameAlg, byte[] akName, byte[] secret) {
        byte[] seed = new byte[digestSize(ekNameAlg)];
        random.nextBytes(seed);
        return make(ekPub, ekNameAlg, akName, secret, seed);
    }

    /** Deterministic core (seed injected) — package-private for the round-trip unit test. */
    Credential make(PublicKey ekPub, int ekNameAlg, byte[] akName, byte[] secret, byte[] seed) {
        if (akName == null || akName.length == 0) {
            throw new IllegalArgumentException("akName (TPM Name) required");
        }
        // V10 contract: the activation credential is the fixed-size AES-128 challenge — enforce it
        // (Codex review) so the API can't be called with an off-policy credential length.
        if (secret == null || secret.length != SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "credential secret must be exactly " + SECRET_BYTES + " bytes (AES-128)");
        }
        String oaepHash = TpmPublicArea.jcaHash(ekNameAlg); // "SHA-256" — throws on weak/unknown
        String hmac = hmacName(ekNameAlg);
        try {
            // encSecret = RSA-OAEP(ekPub, seed) with label "IDENTITY\0"
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec oaep = new OAEPParameterSpec(oaepHash, "MGF1",
                    new MGF1ParameterSpec(oaepHash), new PSource.PSpecified(OAEP_LABEL));
            rsa.init(Cipher.ENCRYPT_MODE, ekPub, oaep);
            byte[] encSecret = rsa.doFinal(seed);

            // symKey = KDFa(STORAGE, contextU=akName, 128) → AES-128-CFB(IV=0) over TPM2B(secret)
            byte[] symKey = TpmKdfa.kdfa(hmac, seed, "STORAGE", akName, new byte[0], 128);
            Cipher aes = Cipher.getInstance("AES/CFB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(symKey, "AES"), new IvParameterSpec(new byte[16]));
            byte[] encIdentity = aes.doFinal(tpm2b(secret));

            // outer integrity HMAC over (encIdentity ‖ akName), keyed by KDFa(INTEGRITY, digestBits)
            byte[] hmacKey = TpmKdfa.kdfa(hmac, seed, "INTEGRITY", new byte[0], new byte[0], digestSize(ekNameAlg) * 8);
            Mac mac = Mac.getInstance(hmac);
            mac.init(new SecretKeySpec(hmacKey, hmac));
            mac.update(encIdentity);
            mac.update(akName);
            byte[] outerHmac = mac.doFinal();

            byte[] idObject = concat(tpm2b(outerHmac), encIdentity);
            return new Credential(idObject, encSecret);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TPM2_MakeCredential failed", e);
        }
    }

    /**
     * Serialize to the tpm2-tools credential file format
     * ({@code 0xBADCC0DE ‖ version=1 ‖ TPM2B(credentialBlob) ‖ TPM2B(encSecret)}) so a real
     * {@code tpm2_activatecredential -i} can recover the secret in the swtpm interop test.
     */
    public static byte[] toToolsFile(Credential c) {
        return concat(TpmKdfa.be32(0xBADCC0DE), TpmKdfa.be32(1),
                tpm2b(c.credentialBlob()), tpm2b(c.encSecret()));
    }

    private static int digestSize(int tpmHashAlg) {
        return switch (tpmHashAlg) {
            case TpmPublicArea.ALG_SHA256 -> 32;
            case TpmPublicArea.ALG_SHA384 -> 48;
            case TpmPublicArea.ALG_SHA512 -> 64;
            default -> throw new IllegalArgumentException("unsupported nameAlg 0x" + Integer.toHexString(tpmHashAlg));
        };
    }

    private static String hmacName(int tpmHashAlg) {
        return switch (tpmHashAlg) {
            case TpmPublicArea.ALG_SHA256 -> "HmacSHA256";
            case TpmPublicArea.ALG_SHA384 -> "HmacSHA384";
            case TpmPublicArea.ALG_SHA512 -> "HmacSHA512";
            default -> throw new IllegalArgumentException("unsupported nameAlg 0x" + Integer.toHexString(tpmHashAlg));
        };
    }

    private static byte[] label(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[b.length + 1]; // append the TCG null terminator
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    private static byte[] tpm2b(byte[] v) {
        byte[] out = new byte[2 + v.length];
        out[0] = (byte) (v.length >> 8);
        out[1] = (byte) v.length;
        System.arraycopy(v, 0, out, 2, v.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
