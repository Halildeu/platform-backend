package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeHeartbeatLossProbeService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeHeartbeatLossProbeService.ProbeOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
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

class RemoteBridgeHeartbeatLossProbeControllerTest {

    private static final String TOKEN = "ref-operator-token-1";
    private static final String OWNER = "operator@acik.com";
    private static final String OTHER = "other@acik.com";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String AUTH = "Bearer " + TOKEN;
    private static final String BASE = "/internal/remote-bridge/operator/sessions/";

    private static RemoteBridgeSessionStore store() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        store.open(new SessionRequest("s-owned", "dev-1", OWNER, "support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new PeerIdentity("peer-1", Optional.of("dev-1"), List.of()), TENANT, "Operator",
                61_000L, 1_000L);
        store.open(new SessionRequest("s-foreign", "dev-1", OTHER, "support",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new PeerIdentity("peer-2", Optional.of("dev-1"), List.of()), TENANT, "Other",
                61_000L, 1_000L);
        return store;
    }

    @Test
    void ownedAuthenticatedProbeReturnsOnlyBoundedTerminationMetadata() throws Exception {
        RemoteBridgeHeartbeatLossProbeService service = mock(RemoteBridgeHeartbeatLossProbeService.class);
        when(service.exercise(any())).thenReturn(new ProbeOutcome(true,
                "control-stream-loss-terminal-observed", "probe-1", 46_000L, "KILLED"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RemoteBridgeHeartbeatLossProbeController(
                service, new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT), store(), false)).build();

        mvc.perform(post(BASE + "s-owned/termination-probes/heartbeat-loss").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("TERMINATED"))
                .andExpect(jsonPath("$.reason").value("control-stream-loss-terminal-observed"))
                .andExpect(jsonPath("$.probeId").value("probe-1"))
                .andExpect(jsonPath("$.terminalState").value("KILLED"));
    }

    @Test
    void heartbeatProbeIsAuthenticationTenantAndOwnerBound() throws Exception {
        RemoteBridgeHeartbeatLossProbeService service = mock(RemoteBridgeHeartbeatLossProbeService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RemoteBridgeHeartbeatLossProbeController(
                service, new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT), store(), false)).build();

        mvc.perform(post(BASE + "s-owned/termination-probes/heartbeat-loss"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "s-foreign/termination-probes/heartbeat-loss").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "missing/termination-probes/heartbeat-loss").header("Authorization", AUTH))
                .andExpect(status().isNotFound());

        verify(service, never()).exercise(any());
    }

    @Test
    void terminalTimeoutMapsToConflictWithoutClaimingSuccess() throws Exception {
        RemoteBridgeHeartbeatLossProbeService service = mock(RemoteBridgeHeartbeatLossProbeService.class);
        when(service.exercise(any())).thenReturn(new ProbeOutcome(false,
                "control-stream-loss-terminal-not-observed", "probe-1", 46_000L, null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RemoteBridgeHeartbeatLossProbeController(
                service, new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT), store(), false)).build();

        mvc.perform(post(BASE + "s-owned/termination-probes/heartbeat-loss").header("Authorization", AUTH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.kind").value("REFUSED"));
    }

    @Test
    void heartbeatProbeControllerIsForbiddenInProductionLikeProfiles() {
        assertThrows(IllegalStateException.class, () -> new RemoteBridgeHeartbeatLossProbeController(
                mock(RemoteBridgeHeartbeatLossProbeService.class),
                new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT),
                new RemoteBridgeSessionStore(), true));

        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");
        assertThrows(IllegalStateException.class, () -> new RemoteBridgeServerConfig()
                .remoteBridgeHeartbeatLossProbeService(new ControlStreamRegistry(),
                        new RemoteBridgeSessionStore(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        production, 45_000L, 50_000L));
    }

    @Test
    void controllerRequiresOperatorRestAndDedicatedProbeFlag() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(RemoteBridgeHeartbeatLossProbeService.class,
                        () -> mock(RemoteBridgeHeartbeatLossProbeService.class))
                .withBean(OperatorAuthenticator.class, () -> new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT))
                .withBean(RemoteBridgeSessionStore.class, RemoteBridgeSessionStore::new)
                .withConfiguration(UserConfigurations.of(RemoteBridgeHeartbeatLossProbeController.class));

        runner.run(context -> assertFalse(context.containsBean("remoteBridgeHeartbeatLossProbeController")));
        runner.withPropertyValues("remote-bridge.operator-rest.enabled=true")
                .run(context -> assertFalse(context.containsBean("remoteBridgeHeartbeatLossProbeController")));
        runner.withPropertyValues("remote-bridge.heartbeat-loss-probes.enabled=true")
                .run(context -> assertFalse(context.containsBean("remoteBridgeHeartbeatLossProbeController")));
        runner.withPropertyValues("remote-bridge.operator-rest.enabled=true",
                        "remote-bridge.heartbeat-loss-probes.enabled=true")
                .run(context -> assertTrue(context.containsBean("remoteBridgeHeartbeatLossProbeController")));
    }
}
