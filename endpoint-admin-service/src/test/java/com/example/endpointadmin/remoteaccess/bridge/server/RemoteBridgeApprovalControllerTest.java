package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.ApprovalFatigueLimiter;
import com.example.endpointadmin.remoteaccess.InMemoryCanonicalIdentityResolver;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalFlow;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalGate;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.TenantScopedAuthzGrantResolver;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalBackedOwnerTokenGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.InMemoryApprovalGrantStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerTokenGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteSessionApprovalRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.6 D10 approval write-path (Codex 019ebe06; PR-2) — the approval REST transport's contract: authenticate
 * first; parse capabilities BEFORE the lookup (400); tenant-filtered lookup; and collapse missing / cross-tenant
 * / EVERY recorder denial to the same 404 (no verdict oracle). The full E10 chain + store are real, so a valid
 * approval records a grant the broker can read.
 */
class RemoteBridgeApprovalControllerTest {

    private static final String TOKEN = "ref-token";
    private static final String OWNER = "operator@acik.com";      // the session's operator (requester)
    private static final String APPROVER = "approver@acik.com";   // a DISTINCT second control
    private static final String UNGRANTED = "stranger@acik.com";  // resolvable but holds no can_approve
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "33333333-3333-3333-3333-333333333333";
    private static final String DEVICE = "22222222-2222-2222-2222-222222222222";
    private static final long NOW = 5_000L;
    private static final String AUTH = "Bearer " + TOKEN;

    private final RemoteBridgeSessionStore sessionStore = new RemoteBridgeSessionStore();
    private final InMemoryApprovalGrantStore grantStore = new InMemoryApprovalGrantStore();
    private RemoteSessionApprovalRecorder recorder;

    @BeforeEach
    void setUp() {
        // OWNER may request in TENANT; APPROVER + OWNER may approve in TENANT (OWNER's approve is for the
        // self-approval test — it must reach the distinctness check, not deny on a missing grant)
        var canonical = new InMemoryCanonicalIdentityResolver(
                Map.of(OWNER, OWNER, APPROVER, APPROVER, UNGRANTED, UNGRANTED));
        var authz = new TenantScopedAuthzGrantResolver(
                Map.of(OWNER, Set.of(TENANT)),
                Map.of(APPROVER, Set.of(TENANT), OWNER, Set.of(TENANT)));
        var gate = new RemoteSessionApprovalGate(canonical, new ApprovalFatigueLimiter(5, 60_000L));
        recorder = new RemoteSessionApprovalRecorder(
                new RemoteSessionApprovalFlow(authz, gate), grantStore, 60_000L);
        openSession("s1", OWNER, TENANT, "peer-1");
        openSession("s-cross", OWNER, OTHER_TENANT, "peer-2");
    }

    private void openSession(String sessionId, String operator, String tenant, String peerKey) {
        sessionStore.open(new SessionRequest(sessionId, DEVICE, operator, null,
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new PeerIdentity(peerKey, Optional.of(DEVICE), List.of()), tenant, "Operator", NOW + 60_000L, NOW);
    }

    private MockMvc mvcAs(String approverSubject, String tenant) {
        var controller = new RemoteBridgeApprovalController(recorder,
                new InMemoryOperatorAuthenticator(TOKEN, approverSubject, tenant), sessionStore, () -> NOW);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String body(String... caps) {
        return "{\"capabilities\":[" + String.join(",",
                java.util.Arrays.stream(caps).map(c -> "\"" + c + "\"").toList()) + "]}";
    }

    @Test
    void anUnauthenticatedApprovalIs401() throws Exception {
        mvcAs(APPROVER, TENANT).perform(post("/internal/remote-bridge/approval/sessions/s1/approve")
                .contentType(MediaType.APPLICATION_JSON).content(body("VIEW_ONLY")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedCapabilitiesAre400BeforeTheLookup() throws Exception {
        MockMvc mvc = mvcAs(APPROVER, TENANT);
        // non-pilot capability
        mvc.perform(post("/internal/remote-bridge/approval/sessions/s1/approve").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON).content(body("FULL_RDP"))).andExpect(status().isBadRequest());
        // empty capability set
        mvc.perform(post("/internal/remote-bridge/approval/sessions/s1/approve").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON).content("{\"capabilities\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMissingSessionIs404() throws Exception {
        mvcAs(APPROVER, TENANT).perform(post("/internal/remote-bridge/approval/sessions/nope/approve")
                .header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body("VIEW_ONLY")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCrossTenantSessionIs404Uniform() throws Exception {
        // s-cross is in OTHER_TENANT; the approver is in TENANT → not visible → same 404 as missing (no oracle)
        mvcAs(APPROVER, TENANT).perform(post("/internal/remote-bridge/approval/sessions/s-cross/approve")
                .header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body("VIEW_ONLY")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aValidApprovalIs200AndRecordsTheGrant() throws Exception {
        mvcAs(APPROVER, TENANT).perform(post("/internal/remote-bridge/approval/sessions/s1/approve")
                .header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body("VIEW_ONLY")))
                .andExpect(status().isOk());
        // the grant is recorded keyed on the SESSION's operator (OWNER) + tenant + incarnation — the broker reads it
        OwnerTokenGate broker = new ApprovalBackedOwnerTokenGate(grantStore);
        assertThat(broker.grantedCapabilities(
                new OwnerTokenGate.OwnerGrantContext("s1", TENANT, OWNER, NOW), NOW + 1))
                .containsExactly(RemoteSessionCapability.VIEW_ONLY);
    }

    @Test
    void aSelfApprovalIs404Uniform() throws Exception {
        // the approver IS the session's operator (OWNER) → E10 self-approval → DENIED → same 404 (no oracle)
        mvcAs(OWNER, TENANT).perform(post("/internal/remote-bridge/approval/sessions/s1/approve")
                .header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body("VIEW_ONLY")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUngrantedApproverIs404Uniform() throws Exception {
        // UNGRANTED holds no can_approve → E10 DENIED_MISSING_GRANT → same 404 (no oracle)
        mvcAs(UNGRANTED, TENANT).perform(post("/internal/remote-bridge/approval/sessions/s1/approve")
                .header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body("VIEW_ONLY")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theApprovalEndpointIsAbsentUnlessApprovalRestIsEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(UserConfigurations.of(ApprovalControllerHolder.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(RemoteBridgeApprovalController.class));
        new ApplicationContextRunner()
                .withConfiguration(UserConfigurations.of(ApprovalControllerHolder.class))
                .withBean(RemoteSessionApprovalRecorder.class, () -> recorder)
                .withBean(OperatorAuthenticator.class, () -> new InMemoryOperatorAuthenticator(TOKEN, APPROVER, TENANT))
                .withBean(RemoteBridgeSessionStore.class, () -> sessionStore)
                .withPropertyValues("remote-bridge.approval-rest.enabled=true")
                .run((AssertableApplicationContext ctx) ->
                        assertThat(ctx).hasSingleBean(RemoteBridgeApprovalController.class));
    }

    /** A holder so the @ConditionalOnProperty-gated controller is the unit under test for the lifecycle check. */
    @ConditionalOnProperty(prefix = "remote-bridge.approval-rest", name = "enabled", havingValue = "true")
    static class ApprovalControllerHolder {
        @org.springframework.context.annotation.Bean
        RemoteBridgeApprovalController remoteBridgeApprovalController(RemoteSessionApprovalRecorder recorder,
                OperatorAuthenticator authenticator, RemoteBridgeSessionStore store) {
            return new RemoteBridgeApprovalController(recorder, authenticator, store);
        }
    }
}
