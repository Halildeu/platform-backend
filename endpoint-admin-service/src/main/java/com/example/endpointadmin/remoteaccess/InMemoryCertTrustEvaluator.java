package com.example.endpointadmin.remoteaccess;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reference {@link CertTrustEvaluator} — DEV/TEST. Models a CRL/OCSP cache: a per-cert trust
 * verdict + the instant it was fetched. It does NOT do real X.509 path-building or network revocation
 * checks (that is the B1.4 transport seam); it lets the B1.2 logic — fail-closed defaults, the freshness
 * window, and the partition path — be proven deterministically without a PKI.
 *
 * <p><b>Fail-closed everywhere:</b> a null/absent cert → {@link TrustDecision#NOT_TRUSTED}; an unreachable
 * source ({@link #setAvailable(boolean) available=false}) → {@link TrustDecision#UNKNOWN}; an unseen cert →
 * {@link TrustDecision#UNKNOWN}; a cached answer older than {@code maxAge} → {@link TrustDecision#STALE}
 * (no grace). Only a seeded {@link TrustDecision#ALLOW} within the freshness window is trustworthy.
 */
public final class InMemoryCertTrustEvaluator implements CertTrustEvaluator {

    private record TrustEntry(TrustDecision decision, Instant fetchedAt) {
    }

    private final ConcurrentHashMap<String, TrustEntry> byThumbprint = new ConcurrentHashMap<>();
    private final Duration maxAge;
    private volatile boolean available = true;

    /** @param maxAge the freshness window — a cached verdict older than this reads STALE (fail-closed). */
    public InMemoryCertTrustEvaluator(Duration maxAge) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("maxAge must be positive");
        }
        this.maxAge = maxAge;
    }

    /** Test hook: simulate the CRL/OCSP source being unreachable (partition) → UNKNOWN (fail-closed). */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    /** Seed/refresh a cert's trust verdict as if fetched at {@code fetchedAt}. */
    public void put(String thumbprint, TrustDecision decision, Instant fetchedAt) {
        if (CertThumbprint.isPresent(thumbprint) && decision != null && fetchedAt != null) {
            byThumbprint.put(thumbprint, new TrustEntry(decision, fetchedAt));
        }
    }

    @Override
    public TrustDecision evaluate(CertRef cert, Instant now) {
        if (cert == null || !cert.isPresent() || now == null) {
            return TrustDecision.NOT_TRUSTED; // no cert presented → fail-closed
        }
        if (!available) {
            return TrustDecision.UNKNOWN; // revocation source unreachable → fail-closed (no fresh status)
        }
        TrustEntry entry = byThumbprint.get(cert.thumbprint());
        if (entry == null || entry.fetchedAt() == null) {
            return TrustDecision.UNKNOWN; // never seen → not implicitly trusted
        }
        // freshness: a cached verdict older than the window is STALE regardless of what it once was —
        // a stale not-revoked answer must NOT keep a since-revoked cert alive (no grace, Codex Q4).
        if (Duration.between(entry.fetchedAt(), now).compareTo(maxAge) > 0) {
            return TrustDecision.STALE;
        }
        return entry.decision();
    }
}
