package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz 22.6 D10 Workstream-0 slice-2 (Codex 019ebe06) — the in-memory REFERENCE {@link ApprovalGrantStore}: a
 * per-key entry of (granted capabilities, expiry) for tests + the disabled-by-default skeleton. A PLACEHOLDER
 * (process-local, lost on restart); the {@link OwnerGrantGateFactory} forbids it in a production-like profile —
 * the durable/distributed (DB / OpenFGA-backed) grant store is the owner-gated live slice.
 *
 * <p><b>Fail-closed:</b> {@link #granted} returns an empty set for a missing OR expired key; {@link #record}
 * refuses a null key or null/empty capabilities (a blank grant would be a fail-open hole). The stored set is an
 * immutable copy.
 */
public final class InMemoryApprovalGrantStore implements ApprovalGrantStore {

    private record Entry(Set<RemoteSessionCapability> capabilities, long expiresAtEpochMillis) {
    }

    private final Map<ApprovalGrantKey, Entry> grants = new ConcurrentHashMap<>();

    @Override
    public void record(ApprovalGrantKey key, Set<RemoteSessionCapability> capabilities, long expiresAtEpochMillis) {
        Objects.requireNonNull(key, "key");
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException("a grant must name at least one capability (no blank grant)");
        }
        grants.put(key, new Entry(Set.copyOf(capabilities), expiresAtEpochMillis));
    }

    @Override
    public Set<RemoteSessionCapability> granted(ApprovalGrantKey key, long nowEpochMillis) {
        if (key == null) {
            return Set.of();
        }
        Entry entry = grants.get(key);
        if (entry == null || nowEpochMillis >= entry.expiresAtEpochMillis()) {
            return Set.of(); // missing or expired → fail-closed (a stale grant must never leak to a reused id)
        }
        return entry.capabilities();
    }
}
