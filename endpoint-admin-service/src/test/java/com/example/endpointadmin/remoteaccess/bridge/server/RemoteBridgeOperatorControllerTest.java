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
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OperatorStepUpHandler;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.OperatorOutcome;
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
    private static final long NOW = 5_000L;
    private static final String AUTH = "Bearer " + TOKEN;
    private static final String BASE = "/internal/remote-bridge/operator/sessions/";

    private final RemoteBridgeOperatorService operatorService = mock(RemoteBridgeOperatorService.class);
    private final OperatorStepUpHandler stepUpHandler = mock(OperatorStepUpHandler.class);
    private final RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        openSession("s-owned", OWNER, "dev-owned", "peer-owned");
        openSession("s-foreign", OTHER, "dev-foreign", "peer-foreign");
        OperatorAuthenticator authenticator = new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT);
        RemoteBridgeOperatorController controller = new RemoteBridgeOperatorController(
                operatorService, stepUpHandler, authenticator, store, () -> NOW);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void openSession(String sessionId, String subject, String deviceId, String peerKey) {
        store.open(new SessionRequest(sessionId, deviceId, subject, null,
                        Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new PeerIdentity(peerKey, Optional.of(deviceId), List.of()), "Operator", NOW + 60_000L, NOW);
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

    // ---- disabled-by-default: the @ConditionalOnProperty(operator-rest.enabled) gate ----

    @Test
    void theControllerIsCreatedOnlyWhenOperatorRestIsEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(RemoteBridgeOperatorService.class, () -> mock(RemoteBridgeOperatorService.class))
                .withBean(OperatorStepUpHandler.class, () -> mock(OperatorStepUpHandler.class))
                .withBean(OperatorAuthenticator.class, () -> new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT))
                .withBean(RemoteBridgeSessionStore.class, RemoteBridgeSessionStore::new)
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
