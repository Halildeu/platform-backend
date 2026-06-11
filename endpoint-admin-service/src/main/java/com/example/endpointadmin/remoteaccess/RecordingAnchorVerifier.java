package com.example.endpointadmin.remoteaccess;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

/**
 * Faz 22.6 C-2 — verifies an out-of-band {@link RecordingAnchor} against a live {@link SessionRecordingChain}
 * (ADR-0034 D3 completeness proof). Confirms the anchor was authentically signed by the recorder key AND
 * that the chain still contains — unaltered — at least the anchored prefix: catches a later truncation (the
 * chain is shorter than the signed count) or a wholesale replacement (the chain's head at the anchored count
 * differs from the signed head, or the chain is internally broken). Fail-closed: anything unverifiable →
 * a non-{@link AnchorVerdict#CONSISTENT} verdict.
 */
public final class RecordingAnchorVerifier {

    /** The audit outcome of comparing a live chain to its last signed anchor. */
    public enum AnchorVerdict {
        /** The anchor is authentically signed and the chain extends the anchored, unaltered prefix. */
        CONSISTENT(true),
        /** The anchor's signature is absent/invalid, or there is nothing to compare → fail-closed. */
        UNSIGNED(false),
        /** The chain is shorter than the signed count — the tail was dropped after anchoring. */
        TRUNCATED(false),
        /** The chain was internally altered, or its head at the anchored count differs from the signed head. */
        REPLACED(false);

        private final boolean sound;

        AnchorVerdict(boolean sound) {
            this.sound = sound;
        }

        /** Whether the recording is provably complete + unaltered up to the anchor. */
        public boolean isSound() {
            return sound;
        }
    }

    private final PublicKey publicKey;
    private final String signatureAlgorithm;

    public RecordingAnchorVerifier(PublicKey publicKey, String signatureAlgorithm) {
        if (publicKey == null) {
            throw new IllegalArgumentException("publicKey must be non-null");
        }
        this.publicKey = publicKey;
        this.signatureAlgorithm = SignatureAlgorithms.require(signatureAlgorithm);
    }

    /** Whether the anchor's signature authentically verifies under the recorder public key (fail-closed). */
    public boolean verifySignature(RecordingAnchor anchor) {
        if (anchor == null || anchor.signature() == null || anchor.signature().isBlank()) {
            return false;
        }
        try {
            byte[] signature = Base64.getDecoder().decode(anchor.signature());
            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(publicKey);
            verifier.update(anchor.canonicalBytes());
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Audit a live chain against its last signed anchor. CONSISTENT only when the anchor is authentically
     * signed, the chain is internally intact, it is at least as long as the signed count, and its head at the
     * anchored count equals the signed head.
     */
    public AnchorVerdict audit(SessionRecordingChain liveChain, RecordingAnchor lastAnchor) {
        if (liveChain == null || lastAnchor == null || !verifySignature(lastAnchor)) {
            return AnchorVerdict.UNSIGNED;
        }
        if (!liveChain.verifyIntegrity()) {
            return AnchorVerdict.REPLACED; // the chain itself was altered/re-ordered/etc.
        }
        long anchoredCount = lastAnchor.count();
        if (liveChain.size() < anchoredCount) {
            return AnchorVerdict.TRUNCATED; // the tail was dropped after the anchor was signed
        }
        String headAtAnchoredCount;
        if (anchoredCount <= 0) {
            headAtAnchoredCount = SessionRecordingChain.GENESIS_HASH;
        } else {
            List<SessionRecordingChain.Entry> entries = liveChain.entries();
            headAtAnchoredCount = entries.get((int) anchoredCount - 1).entryHash();
        }
        if (!lastAnchor.headHash().equals(headAtAnchoredCount)) {
            return AnchorVerdict.REPLACED; // the anchored prefix was re-minted differently
        }
        return AnchorVerdict.CONSISTENT;
    }
}
