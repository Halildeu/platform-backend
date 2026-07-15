package com.example.endpointadmin.remoteaccess.policy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Dedicated Ed25519 signing key plus bounded rotation-overlap verification registry. */
public final class RemoteViewPolicyKeyRegistry {
    public static final String ALGORITHM = "Ed25519";

    public record SignedPayload(String keyId, String signatureBase64) {
    }

    private record VerificationKey(PublicKey key, Instant verifyUntil) {
    }

    private final String activeKeyId;
    private final PrivateKey activePrivateKey;
    private final Map<String, VerificationKey> verificationKeys;
    private final Set<String> revokedKeyIds;

    public RemoteViewPolicyKeyRegistry(RemoteViewPolicyProperties properties) {
        try {
            activeKeyId = required(properties.activeKeyId(), "active-key-id");
            revokedKeyIds = Set.copyOf(properties.revokedKeyIds());
            if (revokedKeyIds.contains(activeKeyId)) {
                throw new IllegalStateException("remote-view-policy active key is revoked");
            }
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            Map<String, VerificationKey> publicKeys = new HashMap<>();
            Set<String> seen = new HashSet<>();
            PrivateKey active = null;
            for (RemoteViewPolicyProperties.SigningKey configured : properties.signingKeys()) {
                String kid = required(configured.keyId(), "signing-keys.key-id");
                if (!seen.add(kid)) {
                    throw new IllegalStateException("duplicate remote-view-policy key id: " + kid);
                }
                PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                        Files.readAllBytes(requiredFile(configured.publicKeyDerPath(), kid + " public key"))));
                Instant verifyUntil = parseVerifyUntil(configured.verifyUntil(), kid.equals(activeKeyId), kid);
                publicKeys.put(kid, new VerificationKey(publicKey, verifyUntil));
                if (kid.equals(activeKeyId)) {
                    active = factory.generatePrivate(new PKCS8EncodedKeySpec(
                            Files.readAllBytes(requiredFile(configured.privateKeyPkcs8Path(), kid + " private key"))));
                } else if (configured.privateKeyPkcs8Path() != null
                        && !configured.privateKeyPkcs8Path().isBlank()) {
                    throw new IllegalStateException("overlap verification keys must not mount private key material");
                }
            }
            if (active == null || !publicKeys.containsKey(activeKeyId)) {
                throw new IllegalStateException("remote-view-policy active signing key is not fully configured");
            }
            activePrivateKey = active;
            verificationKeys = Map.copyOf(publicKeys);
            SignedPayload probe = sign("remote-view-policy-startup-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!verify(probe.keyId(), "remote-view-policy-startup-probe"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8), probe.signatureBase64(), Instant.now())) {
                throw new IllegalStateException("remote-view-policy Ed25519 startup self-check failed");
            }
        } catch (RemoteViewPolicyException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("remote-view-policy Ed25519 registry is invalid; refusing startup", e);
        }
    }

    public SignedPayload sign(byte[] payload) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(activePrivateKey);
            signer.update(payload);
            return new SignedPayload(activeKeyId, Base64.getEncoder().encodeToString(signer.sign()));
        } catch (Exception e) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_SIGNATURE_FAILED,
                    "Policy envelope could not be signed", e);
        }
    }

    public boolean verify(String keyId, byte[] payload, String signatureBase64, Instant now) {
        if (keyId == null || revokedKeyIds.contains(keyId)) {
            return false;
        }
        VerificationKey verificationKey = verificationKeys.get(keyId);
        if (verificationKey == null || (verificationKey.verifyUntil() != null
                && !now.isBefore(verificationKey.verifyUntil()))) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(verificationKey.key());
            verifier.update(payload);
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception ignored) {
            return false;
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    private static Instant parseVerifyUntil(String raw, boolean active, String kid) {
        if (raw == null || raw.isBlank()) {
            if (!active) {
                throw new IllegalStateException("overlap key " + kid + " requires verify-until");
            }
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("invalid verify-until for key " + kid, e);
        }
    }

    private static Path requiredFile(String raw, String label) {
        String value = required(raw, label);
        Path path = Path.of(value);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException(label + " must reference a readable file");
        }
        return path;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("remote-view-policy " + label + " is required");
        }
        return value.strip();
    }
}
