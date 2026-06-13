package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.Verdict;
import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker.BrokerOutcome;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ConnectedDeviceResolver;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OperatorStepUpHandler;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.OperatorOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.SessionOpenOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.6 slice-4c-2b-1 (Codex 019ebe06) — the operator REST transport's contract: authenticate first,
 * enforce ownership (404 for missing OR not-owned alike), delegate to the orchestration beans, map outcomes
 * to status, and never leak the step-up verdict. The orchestration beans are mocked — the broker/step-up
 * logic is already covered by the slice-4b/D tests; here only the transport's own responsibility is exercised.
 */
class RemoteBridgeOperatorControllerTest {

    private static final String TOKEN = "ref-operator-token-1";
    private static final String OWNER = "operator@acik.com";
    private static final String OTHER = "other-operator@acik.com";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "33333333-3333-3333-3333-333333333333";
    private static final String DEVICE_UUID = "22222222-2222-2222-2222-222222222222";
    private static final long NOW = 5_000L;
    private static final String AUTH = "Bearer " + TOKEN;
    private static final String BASE = "/internal/remote-bridge/operator/sessions/";
    private static final String OPEN = "/internal/remote-bridge/operator/sessions";

    private final RemoteBridgeOperatorService operatorService = mock(RemoteBridgeOperatorService.class);
    private final OperatorStepUpHandler stepUpHandler = mock(OperatorStepUpHandler.class);
    private final ConnectedDeviceResolver deviceResolver = mock(ConnectedDeviceResolver.class);
    private final RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        openSession("s-owned", OWNER, TENANT, "dev-owned", "peer-owned");
        openSession("s-foreign", OTHER, TENANT, "dev-foreign", "peer-foreign");
        // same operatorSubject as the authenticated operator, but a DIFFERENT tenant — must NOT be ownable
        openSession("s-cross-tenant", OWNER, OTHER_TENANT, "dev-ct", "peer-ct");
        OperatorAuthenticator authenticator = new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT);
        RemoteBridgeOperatorController controller = new RemoteBridgeOperatorController(
                operatorService, stepUpHandler, authenticator, store, deviceResolver, () -> NOW);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void openSession(String sessionId, String subject, String tenant, String deviceId, String peerKey) {
        store.open(new SessionRequest(sessionId, deviceId, subject, null,
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new PeerIdentity(peerKey, Optional.of(deviceId), List.of()), tenant, "Operator", NOW + 60_000L, NOW);
    }

    // ---- authenticate first ----

    @Test
    void anUnauthenticatedChallengeIs401AndTouchesNoHandler() throws Exception {
        mvc.perform(post(BASE + "s-owned/step-up/challenge")).andExpect(status().isUnauthorized());
        verify(stepUpHandler, never()).issueChallenge(any(), anyLong());
    }

    @Test
    void anUnauthenticatedVerifyIs401() throws Exception {
        mvc.perform(post(BASE + "s-owned/step-up/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientDataJsonB64\":\"c\",\"authenticatorDataB64\":\"a\",\"signatureB64\":\"s\"}"))
                .andExpect(status().isUnauthorized());
        verify(stepUpHandler, never()).verifyAndRecord(any(), any(), anyLong());
    }

    @Test
    void anUnauthenticatedOperationIs401() throws Exception {
        mvc.perform(post(BASE + "s-owned/operations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"operation\":\"SCREEN_VIEW\"}"))
                .andExpect(status().isUnauthorized());
        verify(operatorService, never()).handleOperationRequest(any());
    }

    // ---- ownership: missing AND not-owned both 404 (no existence oracle) ----

    @Test
    void aChallengeForAnotherOperatorsSessionIs404() throws Exception {
        mvc.perform(post(BASE + "s-foreign/step-up/challenge").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        verify(stepUpHandler, never()).issueChallenge(any(), anyLong());
    }

    @Test
    void aChallengeForAnUnknownSessionIs404SameAsNotOwned() throws Exception {
        mvc.perform(post(BASE + "s-does-not-exist/step-up/challenge").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        verify(stepUpHandler, never()).issueChallenge(any(), anyLong());
    }

    @Test
    void anOperationForAnotherOperatorsSessionIs404() throws Exception {
        mvc.perform(post(BASE + "s-foreign/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"operation\":\"SCREEN_VIEW\"}"))
                .andExpect(status().isNotFound());
        verify(operatorService, never()).handleOperationRequest(any());
    }

    @Test
    void aSessionOwnedBySameSubjectInAnotherTenantIs404OnEveryFollowUp() throws Exception {
        // Codex REVISE: ownership is tenant AND subject — the authenticated operator (same subject, tenant
        // TENANT) must NOT act on s-cross-tenant (same subject, OTHER_TENANT). Every follow-up verb → 404, no call.
        mvc.perform(post(BASE + "s-cross-tenant/step-up/challenge").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "s-cross-tenant/step-up/verify").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientDataJsonB64\":\"c\",\"authenticatorDataB64\":\"a\",\"signatureB64\":\"s\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "s-cross-tenant/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"operation\":\"SCREEN_VIEW\"}"))
                .andExpect(status().isNotFound());
        verify(stepUpHandler, never()).issueChallenge(any(), anyLong());
        verify(stepUpHandler, never()).verifyAndRecord(any(), any(), anyLong());
        verify(operatorService, never()).handleOperationRequest(any());
    }

    // ---- step-up challenge ----

    @Test
    void aChallengeForTheOwnSessionReturnsit() throws Exception {
        when(stepUpHandler.issueChallenge(eq("s-owned"), anyLong()))
                .thenReturn(Optional.of(new StepUpChallenge("chal-b64", "https://operator.acik.com", 1234L)));
        mvc.perform(post(BASE + "s-owned/step-up/challenge").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challengeB64").value("chal-b64"))
                .andExpect(jsonPath("$.expectedOrigin").value("https://operator.acik.com"))
                .andExpect(jsonPath("$.issuedAtEpochMillis").value(1234));
    }

    @Test
    void aChallengeTheHandlerRefusesIs409() throws Exception {
        when(stepUpHandler.issueChallenge(eq("s-owned"), anyLong())).thenReturn(Optional.empty());
        mvc.perform(post(BASE + "s-owned/step-up/challenge").header("Authorization", AUTH))
                .andExpect(status().isConflict());
    }

    // ---- step-up verify (no verdict oracle) ----

    @Test
    void aVerifyForTheOwnSessionReturnsVerifiedWithoutLeakingTheVerdict() throws Exception {
        when(stepUpHandler.verifyAndRecord(eq("s-owned"), any(), anyLong()))
                .thenReturn(new StepUpVerification(Verdict.VERIFIED, MethodStrength.WEBAUTHN_USER_VERIFICATION, 2000L));
        mvc.perform(post(BASE + "s-owned/step-up/verify").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientDataJsonB64\":\"c\",\"authenticatorDataB64\":\"a\",\"signatureB64\":\"s\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.achievedStrength").value("WEBAUTHN_USER_VERIFICATION"))
                .andExpect(jsonPath("$.verdict").doesNotExist());
    }

    @Test
    void aFailedVerifyIs200WithVerifiedFalseAndNoStrength() throws Exception {
        // a well-formed but non-verifying assertion is a verification RESULT (200 verified=false), not a transport error
        when(stepUpHandler.verifyAndRecord(eq("s-owned"), any(), anyLong()))
                .thenReturn(new StepUpVerification(Verdict.CHALLENGE_MISMATCH, MethodStrength.NONE, 0L));
        mvc.perform(post(BASE + "s-owned/step-up/verify").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientDataJsonB64\":\"c\",\"authenticatorDataB64\":\"a\",\"signatureB64\":\"s\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.achievedStrength").value("NONE"))
                .andExpect(jsonPath("$.verdict").doesNotExist());
    }

    @Test
    void aVerifyWithNoBodyIs400() throws Exception {
        mvc.perform(post(BASE + "s-owned/step-up/verify").header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
        verify(stepUpHandler, never()).verifyAndRecord(any(), any(), anyLong());
    }

    // ---- operations ----

    @Test
    void anOperationForTheOwnSessionIsDelegatedWithThePathSessionIdAndMapped() throws Exception {
        when(operatorService.handleOperationRequest(any()))
                .thenReturn(new OperatorOutcome(new BrokerOutcome(BrokerOutcome.Kind.DENY, null, null, "policy:x"),
                        false, null));
        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"operation\":\"SCREEN_VIEW\",\"commandLine\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DENY"))
                .andExpect(jsonPath("$.transportPushed").value(false));

        ArgumentCaptor<OperationRequest> captor = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operatorService).handleOperationRequest(captor.capture());
        assertEquals("s-owned", captor.getValue().sessionId(), "the session id must come from the PATH, not the body");
        assertEquals("op-1", captor.getValue().operationId());
        assertEquals(RemoteOperation.SCREEN_VIEW, captor.getValue().operation());
    }

    @Test
    void aRejectedOperationIs422WithTheReason() throws Exception {
        when(operatorService.handleOperationRequest(any()))
                .thenReturn(new OperatorOutcome(null, false, "malformed-request"));
        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"operation\":\"SCREEN_VIEW\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("malformed-request"));
    }

    @Test
    void anUnknownOperationNameIs400AndTouchesNoService() throws Exception {
        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"operation\":\"NOT_A_REAL_OPERATION\"}"))
                .andExpect(status().isBadRequest());
        verify(operatorService, never()).handleOperationRequest(any());
    }

    @Test
    void anOperationWithABlankOperationIdIs400() throws Exception {
        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"\",\"operation\":\"SCREEN_VIEW\"}"))
                .andExpect(status().isBadRequest());
        verify(operatorService, never()).handleOperationRequest(any());
    }

    // ---- openSession: resolve the verified peer (tenant from identity, deviceId from body), then consent flow ----

    private static String openBody(String sessionId, String deviceId) {
        return "{\"sessionId\":\"" + sessionId + "\",\"deviceId\":\"" + deviceId
                + "\",\"reason\":\"support\",\"capabilities\":[\"VIEW_ONLY\"]}";
    }

    @Test
    void anUnauthenticatedOpenSessionIs401AndTouchesNothing() throws Exception {
        mvc.perform(post(OPEN).contentType(MediaType.APPLICATION_JSON).content(openBody("s-new", DEVICE_UUID)))
                .andExpect(status().isUnauthorized());
        verify(deviceResolver, never()).resolveConnectedPeer(any(), any(), any());
        verify(operatorService, never()).openSession(any(), any(), any(), any());
    }

    @Test
    void anOpenSessionWithABlankSessionIdOrDeviceIdIs400() throws Exception {
        // a blank/invalid sessionId is caught by WireContract.isValid BEFORE the resolver (well-formed otherwise)
        mvc.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"\",\"deviceId\":\"" + DEVICE_UUID + "\",\"capabilities\":[\"VIEW_ONLY\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s-new\",\"deviceId\":\"\",\"capabilities\":[\"VIEW_ONLY\"]}"))
                .andExpect(status().isBadRequest());
        verify(deviceResolver, never()).resolveConnectedPeer(any(), any(), any());
    }

    @Test
    void anOpenSessionWithAMalformedDeviceIdIs400AndNeverResolves() throws Exception {
        mvc.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content(openBody("s-new", "not-a-uuid")))
                .andExpect(status().isBadRequest());
        verify(deviceResolver, never()).resolveConnectedPeer(any(), any(), any());
    }

    @Test
    void anOpenSessionWithAnUnknownCapabilityIs400() throws Exception {
        mvc.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s-new\",\"deviceId\":\"" + DEVICE_UUID
                        + "\",\"capabilities\":[\"NOT_A_REAL_CAPABILITY\"]}"))
                .andExpect(status().isBadRequest());
        verify(deviceResolver, never()).resolveConnectedPeer(any(), any(), any());
    }

    @Test
    void anOpenSessionForADeviceWithNoConnectedPeerIs404() throws Exception {
        when(deviceResolver.resolveConnectedPeer(any(), any(), any())).thenReturn(Optional.empty());
        mvc.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content(openBody("s-new", DEVICE_UUID)))
                .andExpect(status().isNotFound());
        verify(operatorService, never()).openSession(any(), any(), any(), any());
    }

    @Test
    void anOpenSessionResolvesThePeerAndDerivesTheOperatorSubjectFromTheIdentity() throws Exception {
        PeerIdentity peer = new PeerIdentity("peer-new", Optional.of("dev"), List.of());
        when(deviceResolver.resolveConnectedPeer(any(), any(), any())).thenReturn(Optional.of(peer));
        when(operatorService.openSession(any(), any(), any(), any()))
                .thenReturn(new SessionOpenOutcome("s-new", true, null));

        mvc.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content(openBody("s-new", DEVICE_UUID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-new"))
                .andExpect(jsonPath("$.consentPromptSent").value(true));

        ArgumentCaptor<SessionRequest> captor = ArgumentCaptor.forClass(SessionRequest.class);
        verify(operatorService).openSession(captor.capture(), eq(peer), eq(TENANT), eq(OWNER));
        assertEquals(OWNER, captor.getValue().operatorSubject(), "operatorSubject must come from the AUTH identity");
        assertEquals(DEVICE_UUID, captor.getValue().deviceId());
    }

    @Test
    void anOpenSessionTheServiceRejectsIs422() throws Exception {
        when(deviceResolver.resolveConnectedPeer(any(), any(), any()))
                .thenReturn(Optional.of(new PeerIdentity("peer-new", Optional.empty(), List.of())));
        when(operatorService.openSession(any(), any(), any(), any()))
                .thenReturn(new SessionOpenOutcome("s-new", false, "consent-prompt-not-delivered"));
        mvc.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content(openBody("s-new", DEVICE_UUID)))
                .andExpect(status().isUnprocessableEntity())
                // the internal reason is NOT echoed — a generic refusal closes the sessionId-collision oracle
                .andExpect(jsonPath("$.reason").value("open-session-refused"));
    }

    @Test
    void anOpenSessionWithMissingEmptyNullOrNonPilotCapabilitiesIs400() throws Exception {
        // every capability problem is rejected BEFORE the resolver (Codex REVISE — no device-state oracle)
        String[] badCapabilityBodies = {
            "{\"sessionId\":\"s-new\",\"deviceId\":\"" + DEVICE_UUID + "\"}",                                  // missing
            "{\"sessionId\":\"s-new\",\"deviceId\":\"" + DEVICE_UUID + "\",\"capabilities\":[]}",              // empty
            "{\"sessionId\":\"s-new\",\"deviceId\":\"" + DEVICE_UUID + "\",\"capabilities\":[null]}",          // null element
            "{\"sessionId\":\"s-new\",\"deviceId\":\"" + DEVICE_UUID + "\",\"capabilities\":[\"FULL_RDP\"]}"   // non-pilot
        };
        for (String body : badCapabilityBodies) {
            mvc.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(deviceResolver, never()).resolveConnectedPeer(any(), any(), any());
    }

    @Test
    void anOpenSessionWithANonWireIdOperatorSubjectIs401AndNeverResolves() throws Exception {
        // a verified subject that is not a valid wire id is an auth/IdP misconfig → fail-closed before the data plane
        RemoteBridgeOperatorController badSubject = new RemoteBridgeOperatorController(operatorService, stepUpHandler,
                new InMemoryOperatorAuthenticator(TOKEN, "bad subject!", TENANT), store, deviceResolver, () -> NOW);
        MockMvc mvcBad = MockMvcBuilders.standaloneSetup(badSubject).build();
        mvcBad.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content(openBody("s-new", DEVICE_UUID)))
                .andExpect(status().isUnauthorized());
        verify(deviceResolver, never()).resolveConnectedPeer(any(), any(), any());
    }

    @Test
    void anOpenSessionCanonicalizesAWhitespacePaddedDeviceId() throws Exception {
        when(deviceResolver.resolveConnectedPeer(any(), any(), any()))
                .thenReturn(Optional.of(new PeerIdentity("peer-new", Optional.empty(), List.of())));
        when(operatorService.openSession(any(), any(), any(), any())).thenReturn(new SessionOpenOutcome("s-new", true, null));

        mvc.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content(openBody("s-new", "  " + DEVICE_UUID + "  ")))
                .andExpect(status().isOk());

        ArgumentCaptor<SessionRequest> captor = ArgumentCaptor.forClass(SessionRequest.class);
        verify(operatorService).openSession(captor.capture(), any(), any(), any());
        // the CANONICAL device id reaches the store, never the raw whitespace-padded body id (no late-reject oracle)
        assertEquals(DEVICE_UUID, captor.getValue().deviceId());
    }

    @Test
    void anOpenSessionWithANonUuidIdentityTenantIs401AndNeverResolves() throws Exception {
        // a verified identity with a non-UUID tenant is unusable for tenant-scoping → fail-closed before the data plane
        RemoteBridgeOperatorController badTenant = new RemoteBridgeOperatorController(operatorService, stepUpHandler,
                new InMemoryOperatorAuthenticator(TOKEN, OWNER, "not-a-uuid"), store, deviceResolver, () -> NOW);
        MockMvc mvcBad = MockMvcBuilders.standaloneSetup(badTenant).build();
        mvcBad.perform(post(OPEN).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content(openBody("s-new", DEVICE_UUID)))
                .andExpect(status().isUnauthorized());
        verify(deviceResolver, never()).resolveConnectedPeer(any(), any(), any());
    }

    // ---- disabled-by-default: the @ConditionalOnProperty(operator-rest.enabled) gate ----

    @Test
    void theControllerIsCreatedOnlyWhenOperatorRestIsEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(RemoteBridgeOperatorService.class, () -> mock(RemoteBridgeOperatorService.class))
                .withBean(OperatorStepUpHandler.class, () -> mock(OperatorStepUpHandler.class))
                .withBean(OperatorAuthenticator.class, () -> new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT))
                .withBean(RemoteBridgeSessionStore.class, RemoteBridgeSessionStore::new)
                .withBean(ConnectedDeviceResolver.class, () -> mock(ConnectedDeviceResolver.class))
                .withConfiguration(UserConfigurations.of(RemoteBridgeOperatorController.class));

        // default (property absent) → fail-closed: no controller bean, no endpoint
        runner.run(context -> assertFalse(context.containsBean("remoteBridgeOperatorController")));
        // explicit false → still absent
        runner.withPropertyValues("remote-bridge.operator-rest.enabled=false")
                .run(context -> assertFalse(context.containsBean("remoteBridgeOperatorController")));
        // enabled → the controller is wired
        runner.withPropertyValues("remote-bridge.operator-rest.enabled=true")
                .run(context -> assertTrue(context.containsBean("remoteBridgeOperatorController")));
    }
}
