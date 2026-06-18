package com.example.endpointadmin.domainops;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DomainOpsExecutionConnectorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-18T09:00:00Z"), ZoneOffset.UTC);
    private static final UUID REQUEST_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TENANT_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID DEVICE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String CREDENTIAL_REF = "vault:domain-ops/pilot";

    private final DomainOpsExecutionConnector connector = new DomainOpsExecutionConnector(
            new DomainOpsExecutionConnectorProperties(
                    true,
                    "dispatch-plan-only",
                    List.of("vault:domain-ops/")),
            CLOCK);
    private final DomainOpsExecutionConnector packageConnector = new DomainOpsExecutionConnector(
            new DomainOpsExecutionConnectorProperties(
                    true,
                    "execution-package",
                    List.of("vault:domain-ops/")),
            CLOCK);

    @Test
    void gpoMsiDeploymentCreatesRedactedDispatchPlan() {
        DomainOpsConnectorDispatchResult result = connector.dispatch(request(
                DomainOpsOperation.ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT,
                gpoMsiPayload(),
                CREDENTIAL_REF,
                CLOCK.instant().plusSeconds(300)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.DISPATCHED);
        assertThat(result.reasonCode()).isEqualTo("gpo-msi-deployment-plan-created");
        assertThat(result.connectorAttemptId()).isEqualTo("domops-1111111122223333");
        assertThat(result.redactedResult())
                .containsEntry("dispatchKind", "GPO_MSI_DEPLOYMENT_PLAN")
                .containsEntry("connectorMode", "dispatch-plan-only")
                .containsEntry("artifactHost", "github.com")
                .containsEntry("artifactSha256", "5cab18d460720e5bf89ddf0038f5b1c4d5ae04afc031dda0dc15d9810c969571")
                .containsEntry("targetComputerCount", 2);
        assertThat(result.redactedResult().get("steps"))
                .asList()
                .containsExactly(
                        "VERIFY_MSI_DIGEST",
                        "STAGE_MSI_ARTIFACT",
                        "ENSURE_GPO_LINK",
                        "APPLY_PILOT_SECURITY_FILTER",
                        "REQUEST_GPUPDATE",
                        "QUEUE_ROLLOUT_COLLECTOR");
        assertRedacted(result);
    }

    @Test
    void rolloutCollectorCreatesRedactedEvidencePlan() {
        DomainOpsConnectorDispatchResult result = connector.dispatch(request(
                DomainOpsOperation.ENDPOINT_AGENT_ROLLOUT_COLLECTOR,
                collectorPayload(),
                CREDENTIAL_REF,
                CLOCK.instant().plusSeconds(300)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.DISPATCHED);
        assertThat(result.reasonCode()).isEqualTo("rollout-collector-plan-created");
        assertThat(result.redactedResult())
                .containsEntry("dispatchKind", "ROLLOUT_COLLECTOR_PLAN")
                .containsEntry("expectedApiHost", "mtls.testai.acik.com")
                .containsEntry("expectedMsiSha256", "5cab18d460720e5bf89ddf0038f5b1c4d5ae04afc031dda0dc15d9810c969571")
                .containsEntry("targetComputerCount", 2)
                .containsEntry("restartServiceBeforeCollect", false);
        assertThat(result.redactedResult().get("evidenceTypes"))
                .asList()
                .containsExactly("AGENT_LOG_TAIL", "GPRESULT_HTML", "SERVICE_STATUS");
        assertRedacted(result);
    }

    @Test
    void gpoMsiDeploymentCreatesSealedExecutionPackage() {
        DomainOpsConnectorDispatchResult result = packageConnector.dispatch(request(
                DomainOpsOperation.ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT,
                gpoMsiPayload(),
                CREDENTIAL_REF,
                CLOCK.instant().plusSeconds(300)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.DISPATCHED);
        assertThat(result.reasonCode()).isEqualTo("gpo-msi-execution-package-created");
        assertThat(result.connectorAttemptId()).isEqualTo("domops-1111111122223333");
        assertThat(result.redactedResult())
                .containsEntry("packageFormat", "domain-ops-execution-package.v1")
                .containsEntry("dispatchKind", "GPO_MSI_EXECUTION_PACKAGE")
                .containsEntry("connectorMode", "execution-package")
                .containsEntry("operation", "ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT")
                .containsEntry("artifactHost", "github.com")
                .containsEntry("artifactSha256", "5cab18d460720e5bf89ddf0038f5b1c4d5ae04afc031dda0dc15d9810c969571")
                .containsEntry("targetComputerCount", 2)
                .containsEntry("ttlSeconds", 300L);
        assertThat(result.redactedResult())
                .containsKeys(
                        "payloadSha256",
                        "credentialRefSha256",
                        "targetComputerSetSha256",
                        "packageSha256");
        assertThat(result.redactedResult().get("expectedEvidenceContract"))
                .asList()
                .containsExactly(
                        "MSI_DIGEST_VERIFIED",
                        "GPO_LINK_SCOPED_TO_PILOT",
                        "PILOT_SECURITY_FILTER_APPLIED",
                        "GPUPDATE_REQUESTED_OR_NATURAL_REFRESH_RECORDED",
                        "ROLLOUT_COLLECTOR_QUEUED");
        assertRedacted(result);
    }

    @Test
    void rolloutCollectorCreatesSealedExecutionPackage() {
        DomainOpsConnectorDispatchResult result = packageConnector.dispatch(request(
                DomainOpsOperation.ENDPOINT_AGENT_ROLLOUT_COLLECTOR,
                collectorPayload(),
                CREDENTIAL_REF,
                CLOCK.instant().plusSeconds(120)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.DISPATCHED);
        assertThat(result.reasonCode()).isEqualTo("rollout-collector-execution-package-created");
        assertThat(result.redactedResult())
                .containsEntry("packageFormat", "domain-ops-execution-package.v1")
                .containsEntry("dispatchKind", "ROLLOUT_COLLECTOR_EXECUTION_PACKAGE")
                .containsEntry("connectorMode", "execution-package")
                .containsEntry("operation", "ENDPOINT_AGENT_ROLLOUT_COLLECTOR")
                .containsEntry("expectedApiHost", "mtls.testai.acik.com")
                .containsEntry("expectedMsiSha256", "5cab18d460720e5bf89ddf0038f5b1c4d5ae04afc031dda0dc15d9810c969571")
                .containsEntry("targetComputerCount", 2)
                .containsEntry("restartServiceBeforeCollect", false)
                .containsEntry("ttlSeconds", 120L);
        assertThat(result.redactedResult())
                .containsKeys(
                        "payloadSha256",
                        "credentialRefSha256",
                        "targetComputerSetSha256",
                        "packageSha256");
        assertThat(result.redactedResult().get("expectedEvidenceContract"))
                .asList()
                .containsExactly(
                        "MACHINE_CLIENT_AUTH_CERT_PRESENT",
                        "ENDPOINT_AGENT_SERVICE_RUNNING_AUTOMATIC_LOCALSYSTEM",
                        "ENDPOINT_AGENT_BINARY_DIGEST_MATCHES_EXPECTED_MSI",
                        "MTLS_HOST_REACHABLE_AND_CONFIGURED",
                        "REDACTED_GPRESULT_OR_EQUIVALENT_POLICY_EVIDENCE",
                        "AGENT_LOG_TAIL_REDACTED");
        assertRedacted(result);
    }

    @Test
    void unsupportedLegacyOperationFailsClosed() {
        DomainOpsConnectorDispatchResult result = connector.dispatch(request(
                DomainOpsOperation.GPO_FORCE_REFRESH,
                Map.of(),
                CREDENTIAL_REF,
                CLOCK.instant().plusSeconds(300)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation-not-supported-by-execution-connector");
        assertThat(result.redactedResult()).isEmpty();
    }

    @Test
    void executionPackageModeRejectsUnsupportedLegacyOperation() {
        DomainOpsConnectorDispatchResult result = packageConnector.dispatch(request(
                DomainOpsOperation.CERT_AUTOENROLL_PULSE,
                Map.of(),
                CREDENTIAL_REF,
                CLOCK.instant().plusSeconds(300)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("operation-not-supported-by-execution-connector");
        assertThat(result.redactedResult()).isEmpty();
    }

    @Test
    void expiredRequestFailsClosedBeforePlanCreation() {
        DomainOpsConnectorDispatchResult result = connector.dispatch(request(
                DomainOpsOperation.ENDPOINT_AGENT_ROLLOUT_COLLECTOR,
                collectorPayload(),
                CREDENTIAL_REF,
                CLOCK.instant()));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("dispatch-window-expired");
        assertThat(result.redactedResult()).isEmpty();
    }

    @Test
    void disallowedCredentialRefFailsClosedWithoutEchoingValue() {
        DomainOpsConnectorDispatchResult result = connector.dispatch(request(
                DomainOpsOperation.ENDPOINT_AGENT_ROLLOUT_COLLECTOR,
                collectorPayload(),
                "local-admin-password",
                CLOCK.instant().plusSeconds(300)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("credential-ref-not-allowed");
        assertThat(result.toString()).doesNotContain("local-admin-password");
    }

    @Test
    void missingCredentialPrefixConfigurationFailsClosed() {
        DomainOpsExecutionConnector noPrefixes = new DomainOpsExecutionConnector(
                new DomainOpsExecutionConnectorProperties(true, "dispatch-plan-only", null),
                CLOCK);

        DomainOpsConnectorDispatchResult result = noPrefixes.dispatch(request(
                DomainOpsOperation.ENDPOINT_AGENT_ROLLOUT_COLLECTOR,
                collectorPayload(),
                CREDENTIAL_REF,
                CLOCK.instant().plusSeconds(300)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("credential-ref-not-allowed");
    }

    @Test
    void credentialRefPrefixConfusionFailsClosed() {
        DomainOpsConnectorDispatchResult result = connector.dispatch(request(
                DomainOpsOperation.ENDPOINT_AGENT_ROLLOUT_COLLECTOR,
                collectorPayload(),
                "vault:domain-ops/../admin/root",
                CLOCK.instant().plusSeconds(300)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("credential-ref-not-allowed");
        assertThat(result.toString()).doesNotContain("admin/root");
    }

    @Test
    void unsupportedConnectorModeFailsClosed() {
        DomainOpsExecutionConnector unsupported = new DomainOpsExecutionConnector(
                new DomainOpsExecutionConnectorProperties(true, "direct-ad-rpc", List.of("vault:domain-ops/")),
                CLOCK);

        DomainOpsConnectorDispatchResult result = unsupported.dispatch(request(
                DomainOpsOperation.ENDPOINT_AGENT_ROLLOUT_COLLECTOR,
                collectorPayload(),
                CREDENTIAL_REF,
                CLOCK.instant().plusSeconds(300)));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("connector-mode-unsupported");
    }

    private static DomainOpsConnectorDispatchRequest request(DomainOpsOperation operation,
                                                             Map<String, Object> payload,
                                                             String credentialRef,
                                                             Instant expiresAt) {
        return new DomainOpsConnectorDispatchRequest(
                REQUEST_ID,
                TENANT_ID,
                DEVICE_ID,
                "AGENTPC2",
                "acik.local",
                operation,
                payload,
                credentialRef,
                expiresAt,
                "pilot validation");
    }

    private static Map<String, Object> gpoMsiPayload() {
        return Map.of(
                "deploymentMethod", "gpo-msi",
                "artifactVersion", "0.2.9",
                "artifactUrl", "https://github.com/Halildeu/platform-agent/releases/download/v0.2.9/EndpointAgent-0.2.9-signed.msi",
                "artifactSha256", "5cab18d460720e5bf89ddf0038f5b1c4d5ae04afc031dda0dc15d9810c969571",
                "pilotOu", "OU=EndpointTest,DC=acik,DC=local",
                "pilotGroup", "EndpointAgentPilotComputers",
                "gpoName", "EndpointAgent Pilot - AGENTPC2",
                "targetComputers", List.of("ERP-MOBIL", "SRB-AIDENETIMPC"),
                "rollbackStrategy", "gpo-unlink-or-security-filter");
    }

    private static Map<String, Object> collectorPayload() {
        return Map.of(
                "expectedApiHost", "mtls.testai.acik.com",
                "expectedMsiSha256", "5cab18d460720e5bf89ddf0038f5b1c4d5ae04afc031dda0dc15d9810c969571",
                "targetComputers", List.of("AGENTPC2", "SRB-AIDENETIMPC"),
                "includeGpResultHtml", true,
                "includeServiceStatus", true,
                "includeAgentLogTail", true,
                "restartServiceBeforeCollect", false);
    }

    private static void assertRedacted(DomainOpsConnectorDispatchResult result) {
        String rendered = result.redactedResult().toString();
        assertThat(rendered)
                .doesNotContain(CREDENTIAL_REF)
                .doesNotContain("/releases/download/")
                .doesNotContain("EndpointAgent-0.2.9-signed.msi")
                .doesNotContain("OU=EndpointTest")
                .doesNotContain("EndpointAgentPilotComputers")
                .doesNotContain("EndpointAgent Pilot - AGENTPC2")
                .doesNotContain("ERP-MOBIL")
                .doesNotContain("SRB-AIDENETIMPC")
                .doesNotContain("AGENTPC2");
    }
}
