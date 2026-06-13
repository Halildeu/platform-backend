package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.AuthzGrantResolver;
import com.example.endpointadmin.remoteaccess.CanonicalIdentityResolver;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalFlow;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.InMemoryApprovalGrantStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10 approval-chain wiring (Codex 019ebe06; PR-1) — the chain beans build outside prod with a complete
 * pilot config, the in-memory placeholders are prod-forbidden, and a missing canonical/grant config fail-fasts.
 * (The end-to-end record behaviour is covered by {@code RemoteSessionApprovalRecorderTest}.)
 */
class RemoteBridgeApprovalConfigTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private final RemoteBridgeApprovalConfig config = new RemoteBridgeApprovalConfig();
    private final Environment nonProd = new StandardEnvironment();

    private static Environment prod() {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    private static RemoteBridgeApprovalProperties props() {
        RemoteBridgeApprovalProperties p = new RemoteBridgeApprovalProperties();
        p.setCanonicalIdentity(Map.of("operator@x", "operator@x", "approver@y", "approver@y"));
        p.getGrants().setCanRequest(Map.of("operator@x", Set.of(TENANT)));
        p.getGrants().setCanApprove(Map.of("approver@y", Set.of(TENANT)));
        return p;
    }

    @Test
    void theChainBuildsOutsideProdWithACompleteConfig() {
        RemoteBridgeApprovalProperties props = props();
        CanonicalIdentityResolver canonical = config.remoteBridgeCanonicalIdentityResolver(nonProd, props);
        AuthzGrantResolver grants = config.remoteBridgeAuthzGrantResolver(nonProd, props);
        RemoteSessionApprovalGate gate = config.remoteBridgeApprovalGate(canonical,
                config.remoteBridgeApprovalFatigueLimiter(nonProd, props));
        RemoteSessionApprovalFlow flow = config.remoteBridgeApprovalFlow(grants, gate);
        assertNotNull(config.remoteBridgeApprovalRecorder(flow, new InMemoryApprovalGrantStore(), props));
        // the authz resolver is the tenant-scoped one: it grants approve for ANY (dynamic) session in the tenant
        assertTrue(grants.hasCanApprove("approver@y", "remote_session:" + TENANT + ":any-dynamic-session"));
    }

    @Test
    void theInMemoryPlaceholdersAreForbiddenInAProdLikeProfile() {
        RemoteBridgeApprovalProperties props = props();
        assertThrows(IllegalStateException.class, () -> config.remoteBridgeCanonicalIdentityResolver(prod(), props));
        assertThrows(IllegalStateException.class, () -> config.remoteBridgeAuthzGrantResolver(prod(), props));
        assertThrows(IllegalStateException.class, () -> config.remoteBridgeApprovalFatigueLimiter(prod(), props));
    }

    @Test
    void aMissingCanonicalMappingFailsFast() {
        RemoteBridgeApprovalProperties empty = new RemoteBridgeApprovalProperties(); // no canonical-identity
        assertThrows(IllegalStateException.class,
                () -> config.remoteBridgeCanonicalIdentityResolver(nonProd, empty));
    }

    @Test
    void aMissingGrantConfigFailsFast() {
        RemoteBridgeApprovalProperties noGrants = props();
        noGrants.getGrants().setCanApprove(Map.of());
        assertThrows(IllegalStateException.class, () -> config.remoteBridgeAuthzGrantResolver(nonProd, noGrants));
    }
}
