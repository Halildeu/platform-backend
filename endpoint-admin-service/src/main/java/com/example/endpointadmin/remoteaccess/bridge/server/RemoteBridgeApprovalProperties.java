package com.example.endpointadmin.remoteaccess.bridge.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Faz 22.6 D10 approval-chain (Codex 019ebe06) — typed config for the (non-prod, opt-in) approval write-path
 * chain. The canonical-identity mapping and the tenant-scoped grants are explicit maps (Codex: typed, no
 * comma-string parsing, no pass-through). Bound under {@code remote-bridge.approval.*}; only consulted when
 * {@code remote-bridge.approval-rest.enabled=true}.
 */
@ConfigurationProperties(prefix = "remote-bridge.approval")
public class RemoteBridgeApprovalProperties {

    /** principal-id → canonical subject (explicit; the {@code InMemoryCanonicalIdentityResolver} fail-closes unmapped). */
    private Map<String, String> canonicalIdentity = new HashMap<>();

    private final Grants grants = new Grants();
    private final Fatigue fatigue = new Fatigue();

    /** TTL of a recorded grant (a recorded approval expires so it cannot outlive the session). */
    private long grantTtlMillis = 300_000L;

    /** Tenant-scoped grants: principal → the tenants in which they may request / approve ANY session. */
    public static class Grants {
        private Map<String, Set<String>> canRequest = new HashMap<>();
        private Map<String, Set<String>> canApprove = new HashMap<>();

        public Map<String, Set<String>> getCanRequest() {
            return canRequest;
        }

        public void setCanRequest(Map<String, Set<String>> canRequest) {
            this.canRequest = canRequest == null ? new HashMap<>() : canRequest;
        }

        public Map<String, Set<String>> getCanApprove() {
            return canApprove;
        }

        public void setCanApprove(Map<String, Set<String>> canApprove) {
            this.canApprove = canApprove == null ? new HashMap<>() : canApprove;
        }
    }

    /** Approval-fatigue cap: at most {@code maxPerWindow} approvals per {@code windowMillis} per canonical approver. */
    public static class Fatigue {
        private int maxPerWindow = 5;
        private long windowMillis = 3_600_000L;

        public int getMaxPerWindow() {
            return maxPerWindow;
        }

        public void setMaxPerWindow(int maxPerWindow) {
            this.maxPerWindow = maxPerWindow;
        }

        public long getWindowMillis() {
            return windowMillis;
        }

        public void setWindowMillis(long windowMillis) {
            this.windowMillis = windowMillis;
        }
    }

    public Map<String, String> getCanonicalIdentity() {
        return canonicalIdentity;
    }

    public void setCanonicalIdentity(Map<String, String> canonicalIdentity) {
        this.canonicalIdentity = canonicalIdentity == null ? new HashMap<>() : canonicalIdentity;
    }

    public Grants getGrants() {
        return grants;
    }

    public Fatigue getFatigue() {
        return fatigue;
    }

    public long getGrantTtlMillis() {
        return grantTtlMillis;
    }

    public void setGrantTtlMillis(long grantTtlMillis) {
        this.grantTtlMillis = grantTtlMillis;
    }

    /** Defensive copies for the resolver constructors (which take {@code Set<String>} tenant sets). */
    static Map<String, Set<String>> copyTenantGrants(Map<String, Set<String>> in) {
        Map<String, Set<String>> out = new HashMap<>();
        if (in != null) {
            in.forEach((principal, tenants) -> out.put(principal,
                    tenants == null ? Set.of() : new HashSet<>(tenants)));
        }
        return out;
    }
}
