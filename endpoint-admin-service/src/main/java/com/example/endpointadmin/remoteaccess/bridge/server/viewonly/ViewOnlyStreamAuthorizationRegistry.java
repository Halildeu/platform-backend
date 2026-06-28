package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Faz 22.6 #1580 (Codex 019f078a) — the VIEW_ONLY fanout authorization gate. A screen frame is fanned out to an
 * operator viewer ONLY for a stream that the broker has authorized: when the operator service pushes a signed
 * {@code VIEW_ONLY} {@code SCREEN_VIEW} permit to the agent it records the authorization here, keyed by
 * {@code (sessionId, streamId)} where {@code streamId == permit.operationId} (the wire binding the agent uses for
 * the screen DATA stream).
 *
 * <p>{@link #isAuthorized} enforces the full fanout gate the handler needs (lock-free, the per-frame data path):
 * an authorization exists for the {@code (sessionId, streamId)}, the authenticated transport peer matches the one
 * the permit was pushed to, and the authorization has not expired. Default with no authorization is fail-closed.
 *
 * <p><b>Incarnation-bound, terminate-wins (Codex 019f0e78 re-review).</b> Authorize/begin/revoke serialize on a
 * control lock and are bound to a per-session <em>incarnation token</em> (the broker {@link Object} identity of
 * the session the permit was minted for):
 * <ul>
 *   <li>{@link #beginSession} records the current incarnation token for a (re)opened session.</li>
 *   <li>{@link #authorize} records a grant ONLY if the caller's incarnation token is the current one — so a late
 *       authorize that races a terminate (same incarnation) or a reopen (a different incarnation reusing the id)
 *       is refused and records nothing.</li>
 *   <li>{@link #revokeSession} sets a TERMINATED marker, so a same-incarnation late authorize is refused until
 *       the next {@link #beginSession}.</li>
 * </ul>
 * The data path stays lock-free because a stale authorize is never written — {@link #isAuthorized} needs no
 * generation check.
 */
public final class ViewOnlyStreamAuthorizationRegistry {

    /**
     * One authorized VIEW_ONLY screen stream.
     *
     * @param sessionId         the remote-support session
     * @param streamId          the authorized DATA stream id (== the SCREEN_VIEW operation id)
     * @param transportPeerKey  the authenticated agent peer the permit was pushed to (binding anchor)
     * @param operatorSubject   the operator the permit was bound to (metadata only)
     * @param deviceId          the target device (metadata only)
     * @param expiresAtEpochMillis the effective authorization expiry (≤ the permit expiry)
     */
    public record Authorization(String sessionId,
                                String streamId,
                                String transportPeerKey,
                                String operatorSubject,
                                String deviceId,
                                long expiresAtEpochMillis) {
        public Authorization {
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId is required");
            }
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("streamId is required");
            }
            if (transportPeerKey == null || transportPeerKey.isBlank()) {
                throw new IllegalArgumentException("transportPeerKey is required");
            }
        }

        boolean isLive(String peerKey, long nowEpochMillis) {
            return transportPeerKey.equals(peerKey) && nowEpochMillis < expiresAtEpochMillis;
        }
    }

    private static final int MAX_TRACKED_SESSIONS = 8192;
    // a single key separator used EVERYWHERE (key build + session-prefix sweeps) so the two can never drift. NUL
    // cannot appear in a wire id (WireContract.isValidId allowlist), so "s1" + SEP can never prefix "s10" + SEP.
    private static final String KEY_SEP = "\u0000";
    // the marker stored for a terminated incarnation — distinct identity that no caller can ever hold.
    private static final Object TERMINATED = new Object();

    private final ConcurrentMap<String, Authorization> byKey = new ConcurrentHashMap<>();
    // control-plane lock serializing authorize / begin / revoke (low-frequency); the data path (isAuthorized)
    // reads byKey lock-free.
    private final Object controlLock = new Object();
    // current incarnation token per session (or TERMINATED); bounded (oldest-evicted), guarded by controlLock.
    // A live session's token is its broker Object identity; a stale authorize whose token != current is refused.
    private final Map<String, Object> incarnationBySession =
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > MAX_TRACKED_SESSIONS;
                }
            };

    private static String key(String sessionId, String streamId) {
        return sessionId + KEY_SEP + streamId;
    }

    private static String sessionPrefix(String sessionId) {
        return sessionId + KEY_SEP;
    }

    /**
     * Start (or restart) a VIEW_ONLY session incarnation: record its incarnation token and clear any stale
     * authorization for a reused {@code sessionId} (the broker session id is client-supplied + reusable, F1).
     * Only an {@link #authorize} carrying THIS exact token will be accepted until the next begin/revoke.
     *
     * @param sessionId   the session
     * @param incarnation the broker incarnation token (the session object identity) — must be non-null
     */
    public void beginSession(String sessionId, Object incarnation) {
        if (sessionId == null) {
            return;
        }
        Objects.requireNonNull(incarnation, "incarnation");
        synchronized (controlLock) {
            incarnationBySession.put(sessionId, incarnation);
            byKey.keySet().removeIf(k -> k.startsWith(sessionPrefix(sessionId)));
        }
    }

    /**
     * Record (or refresh) a VIEW_ONLY stream authorization — called on a VIEW_ONLY permit push. REFUSED (returns
     * {@code false}, records nothing) unless the caller's {@code incarnation} is the CURRENT incarnation for the
     * session: a session terminated since (TERMINATED marker), reopened as a different incarnation, or never
     * begun, all refuse — so neither a same-incarnation terminate race nor a cross-incarnation stale authorize
     * can leave a grant behind. The data path therefore needs no generation check.
     *
     * @return {@code true} if recorded; {@code false} if refused (not the current incarnation)
     */
    public boolean authorize(Object incarnation, Authorization authorization) {
        Objects.requireNonNull(authorization, "authorization");
        synchronized (controlLock) {
            Object current = incarnationBySession.get(authorization.sessionId());
            if (current == null || current == TERMINATED || current != incarnation) {
                return false;
            }
            byKey.put(key(authorization.sessionId(), authorization.streamId()), authorization);
            return true;
        }
    }

    /** The full fanout gate: a live authorization exists for (session, stream) bound to this peer, not expired. */
    public boolean isAuthorized(String sessionId, String streamId, String transportPeerKey, long nowEpochMillis) {
        if (sessionId == null || streamId == null || transportPeerKey == null) {
            return false;
        }
        Authorization authorization = byKey.get(key(sessionId, streamId));
        return authorization != null && authorization.isLive(transportPeerKey, nowEpochMillis);
    }

    public Optional<Authorization> lookup(String sessionId, String streamId) {
        if (sessionId == null || streamId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(key(sessionId, streamId)));
    }

    /** Revoke one stream's authorization (idempotent). */
    public void revokeStream(String sessionId, String streamId) {
        if (sessionId == null || streamId == null) {
            return;
        }
        synchronized (controlLock) {
            byKey.remove(key(sessionId, streamId));
        }
    }

    /**
     * Revoke every authorization for a session AND mark it TERMINATED (called on session terminal — no stale
     * fanout grant, and a late {@link #authorize} for this incarnation is refused until the next
     * {@link #beginSession}).
     */
    public void revokeSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        synchronized (controlLock) {
            incarnationBySession.put(sessionId, TERMINATED);
            byKey.keySet().removeIf(k -> k.startsWith(sessionPrefix(sessionId)));
        }
    }

    /** Drop expired authorizations (housekeeping; authorizations also fail the live-check once expired). */
    public int purgeExpired(long nowEpochMillis) {
        int[] removed = {0};
        byKey.values().removeIf(a -> {
            boolean expired = nowEpochMillis >= a.expiresAtEpochMillis();
            if (expired) {
                removed[0]++;
            }
            return expired;
        });
        return removed[0];
    }
}
