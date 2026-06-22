package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeNegativeProbeService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeNegativeProbeService.ProbeOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RemoteBridgeNegativeProbeControllerTest {

    private static final String TOKEN = "ref-operator-token-1";
    private static final String OWNER = "operator@acik.com";
    private static final String OTHER = "other@acik.com";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final long NOW = 1_000L;
    private static final String AUTH = "Bearer " + TOKEN;
    private static final String BASE = "/internal/remote-bridge/operator/sessions/";

    private static RemoteBridgeSessionStore store() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        store.open(new SessionRequest("s-owned", "dev-1", OWNER, "support",
                        Set.of(RemoteSessionCapability.CONSTRAINED_PTY)),
                new PeerIdentity("peer-1", Optional.of("dev-1"), List.of()), TENANT, "Operator",
                NOW + 60_000L, NOW);
        store.open(new SessionRequest("s-foreign", "dev-1", OTHER, "support",
                        Set.of(RemoteSessionCapability.CONSTRAINED_PTY)),
                new PeerIdentity("peer-2", Optional.of("dev-1"), List.of()), TENANT, "Other",
                NOW + 60_000L, NOW);
        return store;
    }

    @Test
    void expiredProbeAuthenticatesOwnSessionAndReturnsDenyEvidenceAs422() throws Exception {
        RemoteBridgeNegativeProbeService service = mock(RemoteBridgeNegativeProbeService.class);
        RemoteBridgeSessionStore store = store();
        when(service.expiredPermit(any()))
                .thenReturn(new ProbeOutcome(true, "expired-permit-denied", true,
                        RemoteBridgeNegativeProbeService.EXPIRED_PERMIT_DENY_CODE, "op-1", 2L));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RemoteBridgeNegativeProbeController(
                service, new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT), store, false)).build();

        mvc.perform(post(BASE + "s-owned/negative-probes/expired-permit").header("Authorization", AUTH))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.kind").value("DENY"))
                .andExpect(jsonPath("$.reason").value("expired-permit-denied"))
                .andExpect(jsonPath("$.agentErrorCode")
                        .value(RemoteBridgeNegativeProbeService.EXPIRED_PERMIT_DENY_CODE))
                .andExpect(jsonPath("$.transportPushed").value(true));
    }

    @Test
    void probesAreAuthAndOwnershipGated() throws Exception {
        RemoteBridgeNegativeProbeService service = mock(RemoteBridgeNegativeProbeService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RemoteBridgeNegativeProbeController(
                service, new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT), store(), false)).build();

        mvc.perform(post(BASE + "s-owned/negative-probes/replay"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "s-foreign/negative-probes/replay").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "missing/negative-probes/replay").header("Authorization", AUTH))
                .andExpect(status().isNotFound());

        verify(service, never()).replayPermit(any());
    }

    @Test
    void probeRefusalMapsTo409WithBoundedReason() throws Exception {
        RemoteBridgeNegativeProbeService service = mock(RemoteBridgeNegativeProbeService.class);
        when(service.replayPermit(any()))
                .thenReturn(new ProbeOutcome(false, "agent-deny-not-observed", true, null, "op-1", 1L));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RemoteBridgeNegativeProbeController(
                service, new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT), store(), false)).build();

        mvc.perform(post(BASE + "s-owned/negative-probes/replay").header("Authorization", AUTH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.kind").value("REFUSED"))
                .andExpect(jsonPath("$.reason").value("agent-deny-not-observed"));
    }

    @Test
    void probeControllerIsForbiddenInProductionLikeProfiles() {
        assertThrows(IllegalStateException.class, () -> new RemoteBridgeNegativeProbeController(
                mock(RemoteBridgeNegativeProbeService.class),
                new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT),
                new RemoteBridgeSessionStore(),
                true));
    }

    @Test
    void probeControllerRequiresBothOperatorRestAndNegativeProbeProperties() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(RemoteBridgeNegativeProbeService.class, () -> mock(RemoteBridgeNegativeProbeService.class))
                .withBean(OperatorAuthenticator.class, () -> new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT))
                .withBean(RemoteBridgeSessionStore.class, RemoteBridgeSessionStore::new)
                .withConfiguration(UserConfigurations.of(RemoteBridgeNegativeProbeController.class));

        runner.run(context -> assertFalse(context.containsBean("remoteBridgeNegativeProbeController")));
        runner.withPropertyValues("remote-bridge.operator-rest.enabled=true")
                .run(context -> assertFalse(context.containsBean("remoteBridgeNegativeProbeController")));
        runner.withPropertyValues("remote-bridge.negative-probes.enabled=true")
                .run(context -> assertFalse(context.containsBean("remoteBridgeNegativeProbeController")));
        runner.withPropertyValues("remote-bridge.operator-rest.enabled=true",
                        "remote-bridge.negative-probes.enabled=true")
                .run(context -> assertTrue(context.containsBean("remoteBridgeNegativeProbeController")));
    }
}
