package com.example.endpointadmin.remoteaccess;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Faz 22.6 C-2 — signs an out-of-band {@link RecordingAnchor} over a {@link SessionRecordingChain}'s current
 * state with the recorder's private key. The anchor is shipped to a SEPARATE sink so a later truncation /
 * replacement of the chain is detectable (the signed count/head are monotonic + non-forgeable). The
 * algorithm is allowlisted (the signer dictates it).
 */
public final class RecordingAnchorSigner {

    private final String chainId;
    private final PrivateKey signingKey;
    private final String signatureAlgorithm;

    public RecordingAnchorSigner(String chainId, PrivateKey signingKey, String signatureAlgorithm) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId must be non-blank");
        }
        if (signingKey == null) {
            throw new IllegalArgumentException("signingKey must be non-null");
        }
        this.chainId = chainId;
        this.signingKey = signingKey;
        this.signatureAlgorithm = SignatureAlgorithms.require(signatureAlgorithm);
    }

    /**
     * Produce a signed anchor committing the chain's current {@code count} + {@code headHash} at
     * {@code timestampMillis} (caller-supplied; no hidden clock).
     */
    public RecordingAnchor anchor(SessionRecordingChain chain, long timestampMillis) {
        if (chain == null) {
            throw new IllegalArgumentException("chain must be non-null");
        }
        long count = chain.size();
        String headHash = chain.headHash();
        byte[] canonical = RecordingAnchor.canonicalBytes(chainId, count, headHash, timestampMillis);
        try {
            Signature signer = Signature.getInstance(signatureAlgorithm);
            signer.initSign(signingKey);
            signer.update(canonical);
            String signature = Base64.getEncoder().encodeToString(signer.sign());
            return new RecordingAnchor(chainId, count, headHash, timestampMillis, signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("recording anchor signing failed", e);
        }
    }
}
