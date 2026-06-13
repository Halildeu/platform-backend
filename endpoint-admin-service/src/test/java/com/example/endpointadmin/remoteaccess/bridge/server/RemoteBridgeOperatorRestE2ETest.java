package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointMachineCert;
import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.InMemoryOperatorStepUpVerifier;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ConnectedDeviceResolver;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OperatorStepUpHandler;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerTokenGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParser;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TrustEvidenceAssembler;
import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.6 slice-4c-2c (Codex 019ebe06) — the operator-facing REST transport end-to-end with the REAL
 * orchestration chain (NOT mocked): the controller authenticates, the {@link ConnectedDeviceResolver} maps the
 * operator's {@code (tenant, deviceId)} to the live registered peer via the device's active-cert thumbprint
 * (== {@code transportPeerKey}), and {@link RemoteBridgeOperatorService} drives the broker's consent flow,
 * pushing a real consent prompt onto the agent's CONTROL stream. Then a step-up challenge issues, and an
 * operation routes through the REAL broker — with no device PKI (B1.4d) and no verified step-up wired, the
 * honest verdict is DENY (No Fake Work: a PERMIT e2e is owner-gated). This proves the slice-4c seams compose;
 * the unit tests mock these collaborators, this test does not.
 */
class RemoteBridgeOperatorRestE2ETest {

    private static final long NOW = 3_000_000L;
    /** The agent's transport key == the device's enrolled cert thumbprint (both lowercase SHA-256 hex). */
    private static final String THUMBPRINT = "a".repeat(64);
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TOKEN = "ref-operator-token-1";
    private static final String OPERATOR = "operator@acik.com";
    private static final String ORIGIN = "https://operator.acik.com";

    private static RemoteBridgeBroker broker() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(
                    g.generateKeyPair().getPrivate(), "kid-1", RemoteBridgePermitSigner.PERMIT_ALG);
            return new RemoteBridgeBroker(true, RemoteSessionPolicyEngine.PILOT, signer, event -> { }, "rb-v1", 60_000L);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PeerTrustLedger ledger() {
        return new PeerTrustLedger(
                (cert, now) -> CertTrustEvaluator.TrustDecision.NOT_TRUSTED,
                (evidence, now) -> AttestationVerifier.AttestationDecision.MISSING,
                new DeviceIdentityVerifier(Set.of(), DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM),
                PeerEvidenceParser.FAIL_CLOSED, 30_000L);
    }

    private static final class CapturingObserver implements StreamObserver<Envelope> {
        final List<Envelope> sent = new ArrayList<>();

        @Override public void onNext(Envelope value) {
            sent.add(value);
        }

        @Override public void onError(Throwable t) { }

        @Override public void onCompleted() { }
    }

    private ConnectedDeviceResolver resolverFor(ControlStreamRegistry registry) {
        EndpointDevice device = mock(EndpointDevice.class);
        when(device.getStatus()).thenReturn(DeviceStatus.ONLINE);
        when(device.getTenantId()).thenReturn(TENANT);
        EndpointMachineCert cert = mock(EndpointMachineCert.class);
        when(cert.getCertThumbprint()).thenReturn(THUMBPRINT);
        when(cert.getCertNotBefore()).thenReturn(Instant.ofEpochMilli(NOW - 1_000L));
        when(cert.getCertNotAfter()).thenReturn(Instant.ofEpochMilli(NOW + 1_000_000L));
        when(cert.getTenantId()).thenReturn(TENANT);
        when(cert.getDevice()).thenReturn(device);
        EndpointMachineCertRepository repo = mock(EndpointMachineCertRepository.class);
        when(repo.findActiveByTenantIdAndDeviceId(TENANT, DEVICE)).thenReturn(Optional.of(cert));
        return new ConnectedDeviceResolver(repo, registry);
    }

    @Test
    void openSessionThroughTheRestTransportComposesEndToEndWithTheRealChain() throws Exception {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        ControlStreamRegistry registry = new ControlStreamRegistry();
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger(), OwnerTokenGate.DENY_ALL,
                (sid, now) -> DuressSignal.NONE);
        RemoteBridgeOperatorService operator = new RemoteBridgeOperatorService(store, assembler, broker(),
                registry, () -> NOW, 120_000L);

        // the live agent peer, keyed by a canonical-64hex transport key (== the device's enrolled cert thumbprint)
        CapturingObserver agent = new CapturingObserver();
        registry.register(new PeerIdentity(THUMBPRINT, Optional.of("dev-1"), List.of()), new ControlStreamHandle(agent));

        OperatorStepUpHandler stepUp = new OperatorStepUpHandler(
                new InMemoryOperatorStepUpVerifier(MethodStrength.WEBAUTHN_USER_VERIFICATION, ORIGIN), store, ORIGIN, 120_000L);
        RemoteBridgeOperatorController controller = new RemoteBridgeOperatorController(operator, stepUp,
                new InMemoryOperatorAuthenticator(TOKEN, OPERATOR, TENANT.toString()), store, resolverFor(registry), () -> NOW);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String auth = "Bearer " + TOKEN;

        // 1) openSession via REST → the resolver finds the live peer → a REAL consent prompt is pushed to the agent
        mvc.perform(post("/internal/remote-bridge/operator/sessions").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s1\",\"deviceId\":\"" + DEVICE + "\",\"reason\":\"support\","
                        + "\"capabilities\":[\"VIEW_ONLY\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.consentPromptSent").value(true));

        assertTrue(agent.sent.stream().anyMatch(Envelope::hasConsentPrompt),
                "the agent received the consent prompt on its live CONTROL stream (resolver → service → registry)");
        RemoteBridgeSession session = store.bySessionId("s1").orElseThrow();
        assertEquals(State.CONSENT_PENDING, session.state());
        assertEquals(TENANT.toString(), session.operatorTenantId(), "the session is pinned to the AUTHENTICATED tenant");
        assertEquals(OPERATOR, session.operatorSubject());

        // 2) step-up challenge via REST for the operator's OWN (tenant+subject) session → issued
        mvc.perform(post("/internal/remote-bridge/operator/sessions/s1/step-up/challenge").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeB64").isNotEmpty())
                .andExpect(jsonPath("$.expectedOrigin").value(ORIGIN));

        // 3) an operation via REST routes through the REAL broker. With no device PKI / no verified step-up, the
        //    honest verdict on a not-yet-active session is DENY — nothing is forged (No Fake Work).
        int beforeOperation = agent.sent.size();
        mvc.perform(post("/internal/remote-bridge/operator/sessions/s1/operations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"operation\":\"SCREEN_VIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DENY"));
        assertEquals(beforeOperation, agent.sent.size(), "a DENY pushes nothing to the agent");
    }
}
