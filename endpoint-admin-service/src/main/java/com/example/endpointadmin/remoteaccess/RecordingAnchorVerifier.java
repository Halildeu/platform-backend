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
        /** No anchor was supplied (no prior commitment exists) — fail-closed, distinct from a forged one. */
        MISSING_ANCHOR(false),
        /** The anchor's signature is absent/invalid, or its fields are out of range → fail-closed. */
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
        if (liveChain == null || lastAnchor == null) {
            return AnchorVerdict.MISSING_ANCHOR; // no prior commitment to compare against (Codex 019eb7d6 #3)
        }
        if (lastAnchor.count() < 0 || !verifySignature(lastAnchor)) {
            return AnchorVerdict.UNSIGNED; // bad/absent signature, or an out-of-range count (#2)
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

    /**
     * Verify a kept sequence of successive anchors (the operator's audit log) is sound (Codex 019eb7d6 #1):
     * every anchor authentically signed, all for the SAME chain, the {@code count} MONOTONICALLY
     * non-decreasing (a regression = a rollback) with a re-anchor at the same count committing the SAME head,
     * and timestamps non-decreasing. This closes the single-anchor residual: a dropped/replaced tail between
     * two anchors is caught because the later anchor's count/head must extend the earlier one's. Fail-closed:
     * any gap → {@code false}.
     */
    public boolean verifyAnchorSequence(List<RecordingAnchor> anchors) {
        if (anchors == null) {
            return false;
        }
        String chainId = null;
        long prevCount = -1;
        long prevTimestamp = Long.MIN_VALUE;
        String headAtPrevCount = null;
        for (RecordingAnchor a : anchors) {
            if (a == null || a.count() < 0 || !verifySignature(a)) {
                return false; // forged / out-of-range / unsigned
            }
            if (chainId == null) {
                chainId = a.chainId();
            } else if (!chainId.equals(a.chainId())) {
                return false; // all anchors must commit the SAME chain
            }
            if (a.count() < prevCount || a.timestampMillis() < prevTimestamp) {
                return false; // count rollback / time going backwards
            }
            if (a.count() == prevCount && headAtPrevCount != null && !headAtPrevCount.equals(a.headHash())) {
                return false; // a re-anchor at the same count MUST commit the same head (no same-length replace)
            }
            prevCount = a.count();
            prevTimestamp = a.timestampMillis();
            headAtPrevCount = a.headHash();
        }
        return true;
    }
}
