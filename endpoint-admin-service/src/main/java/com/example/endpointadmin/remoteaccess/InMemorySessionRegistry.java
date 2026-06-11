package com.example.endpointadmin.remoteaccess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reference {@link SessionRegistry} — the replica-local set of live remote sessions. This is
 * both the DEV/TEST impl and the shape the runtime broker uses: a session is {@link #put registered}
 * when its tunnel attaches to THIS replica and {@link #remove removed} when it terminates (incl. when
 * the driver kills it). Single-process by design (the locality the lock-free model in {@link
 * SessionRegistry} relies on) — it is NOT shared across replicas.
 *
 * <p>{@link java.util.concurrent.ConcurrentHashMap} backs concurrent register/remove against the
 * driver's poll iteration without external locking; the snapshots returned are point-in-time copies.
 */
public final class InMemorySessionRegistry implements SessionRegistry {

    private final ConcurrentHashMap<String, RemoteSessionHeartbeat.SessionSnapshot> sessions =
            new ConcurrentHashMap<>();

    /** Register/replace a live session this replica owns (keyed by sessionId). No-op on null/blank id. */
    public void put(RemoteSessionHeartbeat.SessionSnapshot snapshot) {
        if (snapshot != null && snapshot.sessionId() != null && !snapshot.sessionId().isBlank()) {
            sessions.put(snapshot.sessionId(), snapshot);
        }
    }

    /** Remove a session on terminate/kill so a poll-recovered kill is only counted once. */
    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** Test/observability helper: how many sessions this replica currently tracks. */
    public int size() {
        return sessions.size();
    }

    @Override
    public List<RemoteSessionHeartbeat.SessionSnapshot> localActiveSessions() {
        List<RemoteSessionHeartbeat.SessionSnapshot> out = new ArrayList<>();
        for (RemoteSessionHeartbeat.SessionSnapshot s : sessions.values()) {
            if (s.state() == RemoteSessionState.ACTIVE) {
                out.add(s);
            }
        }
        return out;
    }

    @Override
    public List<RemoteSessionHeartbeat.SessionSnapshot> findActiveByJti(String jti) {
        if (jti == null || jti.isBlank()) {
            return List.of();
        }
        List<RemoteSessionHeartbeat.SessionSnapshot> out = new ArrayList<>();
        for (RemoteSessionHeartbeat.SessionSnapshot s : sessions.values()) {
            if (s.state() == RemoteSessionState.ACTIVE && jti.equals(s.jti())) {
                out.add(s);
            }
        }
        return out;
    }
}
