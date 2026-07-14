package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.Verdict;
import com.example.endpointadmin.remoteaccess.ApprovedRemoteScriptCatalog;
import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteOperationCatalog;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker.BrokerOutcome;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ConnectedDeviceResolver;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OperatorStepUpHandler;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.OperatorOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.SessionCloseOutcome;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService.SessionDuressSignalOutcome;
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

import static org.hamcrest.Matchers.contains;
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
    private final RemoteOperationCatalog operationCatalog = RemoteOperationCatalog.standard(60_000L);
    private final ApprovedRemoteScriptCatalog approvedScriptCatalog = ApprovedRemoteScriptCatalog.standard(60_000L);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        openSession("s-owned", OWNER, TENANT, "dev-owned", "peer-owned");
        openSession("s-foreign", OTHER, TENANT, "dev-foreign", "peer-foreign");
        // same operatorSubject as the authenticated operator, but a DIFFERENT tenant — must NOT be ownable
        openSession("s-cross-tenant", OWNER, OTHER_TENANT, "dev-ct", "peer-ct");
        OperatorAuthenticator authenticator = new InMemoryOperatorAuthenticator(TOKEN, OWNER, TENANT);
        RemoteBridgeOperatorController controller = new RemoteBridgeOperatorController(
                operatorService, stepUpHandler, authenticator, store, deviceResolver, operationCatalog,
                approvedScriptCatalog, () -> NOW);
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

    @Test
    void anUnauthenticatedDuressSignalIs401() throws Exception {
        mvc.perform(post(BASE + "s-owned/duress/signal").contentType(MediaType.APPLICATION_JSON)
                .content("{\"signal\":\"NONE\"}"))
                .andExpect(status().isUnauthorized());
        verify(operatorService, never()).recordDuressSignal(any(), any(), any());
    }

    @Test
    void anUnauthenticatedCloseIs401AndTouchesNoService() throws Exception {
        mvc.perform(post(BASE + "s-owned/close")).andExpect(status().isUnauthorized());
        verify(operatorService, never()).closeSession(any(), any());
    }

    @Test
    void anUnauthenticatedCatalogQueryIs401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/internal/remote-bridge/operator/operation-catalog"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnauthenticatedApprovedScriptQueryIs401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/internal/remote-bridge/operator/approved-scripts"))
                .andExpect(status().isUnauthorized());
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
    void aCloseForAnotherOperatorsSessionIs404() throws Exception {
        mvc.perform(post(BASE + "s-foreign/close").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        verify(operatorService, never()).closeSession(any(), any());
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
        mvc.perform(post(BASE + "s-cross-tenant/duress/signal").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signal\":\"NONE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "s-cross-tenant/close").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        verify(stepUpHandler, never()).issueChallenge(any(), anyLong());
        verify(stepUpHandler, never()).verifyAndRecord(any(), any(), anyLong());
        verify(operatorService, never()).handleOperationRequest(any());
        verify(operatorService, never()).recordDuressSignal(any(), any(), any());
        verify(operatorService, never()).closeSession(any(), any());
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

    // ---- duress signal ----

    @Test
    void aDuressNoneSignalForTheOwnSessionIsRecorded() throws Exception {
        when(operatorService.recordDuressSignal("s-owned", OWNER,
                com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal.NONE))
                .thenReturn(new SessionDuressSignalOutcome("s-owned", true, false, null));

        mvc.perform(post(BASE + "s-owned/duress/signal").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signal\":\"NONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signal").value("NONE"))
                .andExpect(jsonPath("$.terminal").value(false));
    }

    @Test
    void aPositiveDuressSignalCanTerminalizeTheSession() throws Exception {
        when(operatorService.recordDuressSignal("s-owned", OWNER,
                com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal.PANIC_SIGNAL))
                .thenReturn(new SessionDuressSignalOutcome("s-owned", true, true, null));

        mvc.perform(post(BASE + "s-owned/duress/signal").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signal\":\"PANIC_SIGNAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signal").value("PANIC_SIGNAL"))
                .andExpect(jsonPath("$.terminal").value(true));
    }

    @Test
    void malformedOrAmbiguousDuressSignalIs400() throws Exception {
        String[] badBodies = {
                "{}",
                "{\"signal\":\"\"}",
                "{\"signal\":\"NOT_REAL\"}",
                "{\"signal\":\"AMBIGUOUS\"}"
        };
        for (String body : badBodies) {
            mvc.perform(post(BASE + "s-owned/duress/signal").header("Authorization", AUTH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(operatorService, never()).recordDuressSignal(any(), any(), any());
    }

    @Test
    void aServiceRejectedDuressSignalIs409WithoutInternalReason() throws Exception {
        when(operatorService.recordDuressSignal("s-owned", OWNER,
                com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal.NONE))
                .thenReturn(new SessionDuressSignalOutcome("s-owned", false, false, "recording-failed"));

        mvc.perform(post(BASE + "s-owned/duress/signal").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signal\":\"NONE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("duress-signal-refused"));
    }

    @Test
    void aVerifyWithNoBodyIs400() throws Exception {
        mvc.perform(post(BASE + "s-owned/step-up/verify").header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
        verify(stepUpHandler, never()).verifyAndRecord(any(), any(), anyLong());
    }

    // ---- operations ----

    @Test
    void theOperationCatalogListsEnabledAndDisabledServerOwnedOperations() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/internal/remote-bridge/operator/operation-catalog").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'GET_HOSTNAME')].enabled").value(contains(true)))
                .andExpect(jsonPath("$[?(@.id == 'GET_HOSTNAME')].operation").value(contains("PTY_COMMAND")))
                .andExpect(jsonPath("$[?(@.id == 'GET_HOSTNAME')].approvalRequirement")
                        .value(contains("WEBAUTHN_STEP_UP")))
                .andExpect(jsonPath("$[?(@.id == 'GET_SERVICE_STATUS')].enabled").value(contains(false)))
                .andExpect(jsonPath("$[?(@.id == 'GET_SERVICE_STATUS')].disabledReason")
                        .value(contains("service-status-argument-policy-not-implemented")));
    }

    @Test
    void theApprovedScriptCatalogListsMetadataWithoutScriptBodyOrCommandTemplate() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/internal/remote-bridge/operator/approved-scripts").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.scriptId == 'DIAG_HOSTNAME')].enabled").value(contains(true)))
                .andExpect(jsonPath("$[?(@.scriptId == 'DIAG_HOSTNAME')].version").value(contains("1")))
                .andExpect(jsonPath("$[?(@.scriptId == 'DIAG_HOSTNAME')].scriptHash").isNotEmpty())
                .andExpect(jsonPath("$[?(@.scriptId == 'DIAG_HOSTNAME')].approvalRequirements[0]")
                        .value(contains("WEBAUTHN_STEP_UP")))
                .andExpect(jsonPath("$[?(@.scriptId == 'DIAG_HOSTNAME')].scriptBody").doesNotExist())
                .andExpect(jsonPath("$[?(@.scriptId == 'DIAG_HOSTNAME')].commandTemplate").doesNotExist())
                .andExpect(jsonPath("$[?(@.scriptId == 'COLLECT_SUPPORT_BUNDLE')].revoked").value(contains(true)));
    }

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
                .andExpect(jsonPath("$.transportPushed").value(false))
                .andExpect(jsonPath("$.deny.reason").value("policy:x"))
                .andExpect(jsonPath("$.deny.policyGate").doesNotExist());

        ArgumentCaptor<OperationRequest> captor = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operatorService).handleOperationRequest(captor.capture());
        assertEquals("s-owned", captor.getValue().sessionId(), "the session id must come from the PATH, not the body");
        assertEquals("op-1", captor.getValue().operationId());
        assertEquals(RemoteOperation.SCREEN_VIEW, captor.getValue().operation());
    }

    @Test
    void aCatalogOperationIsDelegatedWithTheServerOwnedCommand() throws Exception {
        when(operatorService.handleOperationRequest(any()))
                .thenReturn(new OperatorOutcome(new BrokerOutcome(BrokerOutcome.Kind.DENY, null, null, "policy:x"),
                        false, null));

        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-2\",\"catalogOperationId\":\"GET_HOSTNAME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DENY"))
                .andExpect(jsonPath("$.catalogOperationId").value("GET_HOSTNAME"));

        ArgumentCaptor<OperationRequest> captor = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operatorService).handleOperationRequest(captor.capture());
        assertEquals("s-owned", captor.getValue().sessionId());
        assertEquals("op-2", captor.getValue().operationId());
        assertEquals(RemoteOperation.PTY_COMMAND, captor.getValue().operation());
        assertEquals("hostname", captor.getValue().commandLine());
    }

    @Test
    void anApprovedScriptInvocationIsDelegatedWithTheServerOwnedCommand() throws Exception {
        ApprovedRemoteScriptCatalog.Definition script = approvedScriptCatalog.find("DIAG_HOSTNAME", "1").orElseThrow();
        when(operatorService.handleOperationRequest(any()))
                .thenReturn(new OperatorOutcome(new BrokerOutcome(BrokerOutcome.Kind.DENY, null, null, "policy:x"),
                        false, null));

        mvc.perform(post(BASE + "s-owned/approved-scripts").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvedScriptBody("op-script-1", script)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DENY"))
                .andExpect(jsonPath("$.approvedScript.scriptId").value("DIAG_HOSTNAME"))
                .andExpect(jsonPath("$.approvedScript.version").value("1"))
                .andExpect(jsonPath("$.approvedScript.scriptHash").value(script.scriptBodySha256()))
                .andExpect(jsonPath("$.approvedScript.approvalRequirements[0]").value("WEBAUTHN_STEP_UP"))
                .andExpect(jsonPath("$.approvedScript.scriptBody").doesNotExist())
                .andExpect(jsonPath("$.approvedScript.commandTemplate").doesNotExist());

        ArgumentCaptor<OperationRequest> captor = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operatorService).handleOperationRequest(captor.capture());
        assertEquals("s-owned", captor.getValue().sessionId());
        assertEquals("op-script-1", captor.getValue().operationId());
        assertEquals(RemoteOperation.PTY_COMMAND, captor.getValue().operation());
        assertEquals("hostname", captor.getValue().commandLine());
    }

    @Test
    void approvedScriptNegativeRequestsFailClosedBeforeService() throws Exception {
        ApprovedRemoteScriptCatalog.Definition script = approvedScriptCatalog.find("DIAG_HOSTNAME", "1").orElseThrow();
        ApprovedRemoteScriptCatalog.Definition disabled = approvedScriptCatalog.find("DIAG_IPCONFIG", "1").orElseThrow();
        ApprovedRemoteScriptCatalog.Definition revoked = approvedScriptCatalog.find("COLLECT_SUPPORT_BUNDLE", "1")
                .orElseThrow();

        mvc.perform(post(BASE + "s-owned/approved-scripts").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvedScriptBody("op-raw", script, "\"rawScriptText\":\"hostname\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("approved-script-raw-text-denied"));
        mvc.perform(post(BASE + "s-owned/approved-scripts").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-hash\",\"scriptId\":\"DIAG_HOSTNAME\",\"scriptVersion\":\"1\","
                        + "\"scriptHash\":\"" + "0".repeat(64) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("approved-script-hash-mismatch"));
        mvc.perform(post(BASE + "s-owned/approved-scripts").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvedScriptBody("op-disabled", disabled)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("approved-script-disabled"));
        mvc.perform(post(BASE + "s-owned/approved-scripts").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvedScriptBody("op-revoked", revoked)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("approved-script-revoked"));
        mvc.perform(post(BASE + "s-owned/approved-scripts").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvedScriptBody("op-args", script, "\"args\":{\"extra\":\"value\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("approved-script-arg-schema-invalid"));

        verify(operatorService, never()).handleOperationRequest(any());
    }

    @Test
    void approvedScriptForAnotherOperatorsSessionIs404AndTouchesNoService() throws Exception {
        ApprovedRemoteScriptCatalog.Definition script = approvedScriptCatalog.find("DIAG_HOSTNAME", "1").orElseThrow();
        mvc.perform(post(BASE + "s-foreign/approved-scripts").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvedScriptBody("op-script-1", script)))
                .andExpect(status().isNotFound());
        verify(operatorService, never()).handleOperationRequest(any());
    }

    @Test
    void ptyCommandWithoutCatalogIdIs400AndTouchesNoService() throws Exception {
        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"operation\":\"PTY_COMMAND\",\"commandLine\":\"hostname\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("catalog-operation-required"));
        verify(operatorService, never()).handleOperationRequest(any());
    }

    @Test
    void unknownDisabledOrOverriddenCatalogOperationFailsClosedBeforeService() throws Exception {
        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"catalogOperationId\":\"NOT_IN_CATALOG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("catalog-operation-unknown"));

        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"catalogOperationId\":\"GET_SERVICE_STATUS\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("catalog-operation-disabled"));

        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"catalogOperationId\":\"GET_HOSTNAME\","
                        + "\"commandLine\":\"hostname\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("catalog-command-override"));

        verify(operatorService, never()).handleOperationRequest(any());
    }

    @Test
    void aPermittedOperationReturnsOnlyRedactedPermitMetadata() throws Exception {
        OperationPermit permit = new OperationPermit(
                RemoteBridgePermitSigner.PERMIT_ALG,
                "rb-permit-kid-1",
                RemoteBridgePermitSigner.PERMIT_VERSION,
                "rb-v1",
                "decision-1",
                "s-owned",
                "op-1",
                "dev-owned",
                OWNER,
                RemoteSessionCapability.VIEW_ONLY,
                "",
                NOW - 100,
                NOW + 60_000,
                7,
                "redacted-signature-b64");
        when(operatorService.handleOperationRequest(any()))
                .thenReturn(new OperatorOutcome(new BrokerOutcome(BrokerOutcome.Kind.PERMIT, permit, null,
                        "permitted"), true, null));

        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"operation\":\"SCREEN_VIEW\",\"commandLine\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PERMIT"))
                .andExpect(jsonPath("$.transportPushed").value(true))
                .andExpect(jsonPath("$.permit.alg").value(RemoteBridgePermitSigner.PERMIT_ALG))
                .andExpect(jsonPath("$.permit.kid").value("rb-permit-kid-1"))
                .andExpect(jsonPath("$.permit.permitVersion").value(RemoteBridgePermitSigner.PERMIT_VERSION))
                .andExpect(jsonPath("$.permit.policyVersion").value("rb-v1"))
                .andExpect(jsonPath("$.permit.decisionId").value("decision-1"))
                .andExpect(jsonPath("$.permit.sessionId").value("s-owned"))
                .andExpect(jsonPath("$.permit.operationId").value("op-1"))
                .andExpect(jsonPath("$.permit.capability").value("VIEW_ONLY"))
                .andExpect(jsonPath("$.permit.commandHash").value(""))
                .andExpect(jsonPath("$.permit.seq").value(7))
                .andExpect(jsonPath("$.permit.signaturePresent").value(true))
                .andExpect(jsonPath("$.permit.freshAtResponseTime").value(true))
                .andExpect(jsonPath("$.permit.canonicalPayloadSha256").exists())
                .andExpect(jsonPath("$.permit.deviceIdSha256").exists())
                .andExpect(jsonPath("$.permit.operatorSubjectSha256").exists())
                .andExpect(jsonPath("$.deny").doesNotExist())
                .andExpect(jsonPath("$.permit.signatureB64").doesNotExist())
                .andExpect(jsonPath("$.permit.deviceId").doesNotExist())
                .andExpect(jsonPath("$.permit.operatorSubject").doesNotExist());
    }

    @Test
    void aPolicyDeniedOperationReturnsOnlyRedactedDenyMetadata() throws Exception {
        when(operatorService.handleOperationRequest(any()))
                .thenReturn(new OperatorOutcome(new BrokerOutcome(BrokerOutcome.Kind.DENY, null, null,
                        "policy:CRYPTO_IDENTITY", "no-active-enrolled-connected-peer"), false, null));

        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"catalogOperationId\":\"GET_HOSTNAME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DENY"))
                .andExpect(jsonPath("$.transportPushed").value(false))
                .andExpect(jsonPath("$.permit").doesNotExist())
                .andExpect(jsonPath("$.deny.reason").value("policy:CRYPTO_IDENTITY"))
                .andExpect(jsonPath("$.deny.policyGate").value("CRYPTO_IDENTITY"))
                .andExpect(jsonPath("$.deny.policyDetail").value("no-active-enrolled-connected-peer"))
                .andExpect(jsonPath("$.deny.deviceId").doesNotExist())
                .andExpect(jsonPath("$.deny.operatorSubject").doesNotExist())
                .andExpect(jsonPath("$.deny.signatureB64").doesNotExist());
    }

    @Test
    void unsafePolicyDetailIsNotReturned() throws Exception {
        when(operatorService.handleOperationRequest(any()))
                .thenReturn(new OperatorOutcome(new BrokerOutcome(BrokerOutcome.Kind.DENY, null, null,
                        "policy:CRYPTO_IDENTITY", "thumbprint:abcdef"), false, null));

        mvc.perform(post(BASE + "s-owned/operations").header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"catalogOperationId\":\"GET_HOSTNAME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DENY"))
                .andExpect(jsonPath("$.deny.reason").value("policy:CRYPTO_IDENTITY"))
                .andExpect(jsonPath("$.deny.policyGate").value("CRYPTO_IDENTITY"))
                .andExpect(jsonPath("$.deny.policyDetail").doesNotExist());
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

    @Test
    void aCloseForTheOwnSessionDelegatesAndReturns204() throws Exception {
        when(operatorService.closeSession("s-owned", OWNER))
                .thenReturn(new SessionCloseOutcome("s-owned", true, true, null));

        mvc.perform(post(BASE + "s-owned/close").header("Authorization", AUTH))
                .andExpect(status().isNoContent());

        verify(operatorService).closeSession("s-owned", OWNER);
    }

    @Test
    void aCloseTheServiceRefusesIs409WithGenericReason() throws Exception {
        when(operatorService.closeSession("s-owned", OWNER))
                .thenReturn(new SessionCloseOutcome("s-owned", false, false, "recording-failed"));

        mvc.perform(post(BASE + "s-owned/close").header("Authorization", AUTH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("session-close-refused"));
    }

    // ---- openSession: resolve the verified peer (tenant from identity, deviceId from body), then consent flow ----

    private static String openBody(String sessionId, String deviceId) {
        return "{\"sessionId\":\"" + sessionId + "\",\"deviceId\":\"" + deviceId
                + "\",\"reason\":\"support\",\"capabilities\":[\"VIEW_ONLY\"]}";
    }

    private static String approvedScriptBody(String operationId, ApprovedRemoteScriptCatalog.Definition script,
                                             String... extraFields) {
        StringBuilder body = new StringBuilder("{\"operationId\":\"").append(operationId)
                .append("\",\"scriptId\":\"").append(script.scriptId())
                .append("\",\"scriptVersion\":\"").append(script.version())
                .append("\",\"scriptHash\":\"").append(script.scriptBodySha256()).append("\"");
        for (String field : extraFields) {
            body.append(',').append(field);
        }
        return body.append('}').toString();
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
                new InMemoryOperatorAuthenticator(TOKEN, "bad subject!", TENANT), store, deviceResolver,
                operationCatalog, approvedScriptCatalog, () -> NOW);
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
                new InMemoryOperatorAuthenticator(TOKEN, OWNER, "not-a-uuid"), store, deviceResolver,
                operationCatalog, approvedScriptCatalog, () -> NOW);
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
                .withBean(RemoteOperationCatalog.class, () -> RemoteOperationCatalog.standard(60_000L))
                .withBean(ApprovedRemoteScriptCatalog.class, () -> ApprovedRemoteScriptCatalog.standard(60_000L))
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
