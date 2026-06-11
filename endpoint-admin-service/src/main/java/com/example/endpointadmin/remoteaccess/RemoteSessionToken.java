package com.example.endpointadmin.remoteaccess;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Faz 22.6 session token contract (ADR-0033 §5). Single-use, short-TTL (≤4h hard cap), bound to
 * exactly one session + target device + operator + audience, carrying a broker-approved capability
 * allowlist. The token is a value object only — minting/signing/mTLS-binding live behind the broker
 * (and are NOT part of this disabled-by-default skeleton).
 *
 * @param jti                unique token id (replay-cache key)
 * @param kid                signing key id (audit linkage)
 * @param sessionId          the one session this token authorizes
 * @param targetDeviceId     the one device this token authorizes
 * @param operatorUserId     the one operator principal this token is bound to (pass-the-hash guard)
 * @param audience           intended audience (broker/relay) — token cannot be replayed elsewhere
 * @param approvedCapabilities broker-computed capability allowlist (agent may only downscope)
 * @param issuedAt           issuance time
 * @param expiresAt          expiry (must be ≤ issuedAt + 4h)
 */
public record RemoteSessionToken(
        String jti,
        String kid,
        String sessionId,
        String targetDeviceId,
        String operatorUserId,
        String audience,
        Set<RemoteSessionCapability> approvedCapabilities,
        Instant issuedAt,
        Instant expiresAt) {

    /** Hard cap on token lifetime (ADR-0033 §5). Pilot default should be shorter. */
    public static final Duration MAX_TTL = Duration.ofHours(4);

    public RemoteSessionToken {
        approvedCapabilities = approvedCapabilities == null ? Set.of() : Set.copyOf(approvedCapabilities);
    }

    /** Structural validity (independent of clock): TTL positive AND within the 4h hard cap. */
    public boolean ttlWithinHardCap() {
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            return false;
        }
        return !Duration.between(issuedAt, expiresAt).minus(MAX_TTL).isPositive();
    }

    public boolean isExpiredAt(Instant now) {
        return now == null || expiresAt == null || !now.isBefore(expiresAt);
    }

    /** Token is bound to this exact (session, device, operator) tuple — no cross-binding reuse. */
    public boolean isBoundTo(String sessionId, String targetDeviceId, String operatorUserId) {
        return this.sessionId != null && this.sessionId.equals(sessionId)
                && this.targetDeviceId != null && this.targetDeviceId.equals(targetDeviceId)
                && this.operatorUserId != null && this.operatorUserId.equals(operatorUserId);
    }
}
