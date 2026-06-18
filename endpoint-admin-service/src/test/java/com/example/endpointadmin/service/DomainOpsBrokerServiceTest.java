package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.domainops.DomainOpsConnector;
import com.example.endpointadmin.domainops.DomainOpsConnectorDispatchRequest;
import com.example.endpointadmin.domainops.DomainOpsConnectorDispatchResult;
import com.example.endpointadmin.domainops.DomainOpsStatus;
import com.example.endpointadmin.dto.v1.admin.CreateDomainOpsRequest;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDomainOpsRequest;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointDomainOpsRequestRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointAuditService.class,
        NoOpAuditChainLock.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DomainOpsBrokerServiceTest {

    private static final UUID TENANT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String SUBJECT = "admin@example.com";
    private static final String CREDENTIAL_REF = "vault:domain-ops/pilot";

    @Autowired private EndpointDeviceRepository deviceRepository;
    @Autowired private EndpointDomainOpsRequestRepository domainOpsRequestRepository;
    @Autowired private EndpointAuditEventRepository auditRepository;
    @Autowired private EndpointAuditService auditService;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        domainOpsRequestRepository.deleteAll();
        auditRepository.deleteAll();
        deviceRepository.deleteAll();
    }

    @Test
    void disabledBrokerDeniesAndPersistsAuditWithoutDurableRequest() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(false, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "DOMAIN_SECURE_CHANNEL_VERIFY", 300, CREDENTIAL_REF)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getEventType()).isEqualTo(DomainOpsBrokerService.EVENT_TYPE_DENIED);
        assertThat(event.getAction()).isEqualTo("domain-ops.denied");
        assertThat(event.getPerformedBySubject()).isEqualTo(SUBJECT);
        assertThat(event.getDevice()).isNull();
        assertThat(event.getMetadata())
                .containsEntry("status", DomainOpsStatus.DENIED.name())
                .containsEntry("deviceId", device.getId().toString())
                .containsEntry("operation", "DOMAIN_SECURE_CHANNEL_VERIFY")
                .containsEntry("reasonCode", "domain-ops-disabled")
                .containsEntry("credentialRefPresent", true)
                .containsEntry("credentialRefAccepted", false);
        assertThat(event.getMetadata().get("ttlSeconds").toString()).isEqualTo("300");
        assertNoRawCredentialInAudit(event);
        assertThat(domainOpsRequestRepository.findAll()).isEmpty();
    }

    @Test
    void enabledBrokerRejectsMissingCredentialRefWithDurableDeniedState() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "gpo-force-refresh", 600, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        EndpointDomainOpsRequest stored = onlyDomainOpsRequest();
        assertThat(stored.getState()).isEqualTo(DomainOpsStatus.DENIED);
        assertThat(stored.getReasonCode()).isEqualTo("credential-ref-required");
        assertThat(stored.getCredentialRef()).isNull();
        assertThat(stored.getCredentialRefHash()).isNull();
        assertThat(stored.getCompletedAt()).isNotNull();

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getEventType()).isEqualTo(DomainOpsBrokerService.EVENT_TYPE_DENIED);
        assertThat(event.getMetadata())
                .containsEntry("operation", "GPO_FORCE_REFRESH")
                .containsEntry("status", DomainOpsStatus.DENIED.name())
                .containsEntry("reasonCode", "credential-ref-required")
                .containsEntry("credentialRefPresent", false)
                .containsEntry("credentialRefAccepted", false);
        assertNoRawCredentialInAudit(event);
    }

    @Test
    void enabledBrokerRejectsUnsafeCredentialRefWithoutLeakingRawValue() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "DOMAIN_SECURE_CHANNEL_VERIFY", 300, "password=not-a-ref")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        EndpointDomainOpsRequest stored = onlyDomainOpsRequest();
        assertThat(stored.getState()).isEqualTo(DomainOpsStatus.DENIED);
        assertThat(stored.getReasonCode()).isEqualTo("credential-ref-invalid");
        assertThat(stored.getCredentialRef()).isNull();
        assertThat(stored.getCredentialRefHash()).isNull();

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getMetadata())
                .containsEntry("credentialRefPresent", true)
                .containsEntry("credentialRefAccepted", false)
                .containsEntry("credentialRefHash", null);
        assertThat(event.getMetadata().toString()).doesNotContain("password=not-a-ref");
    }

    @Test
    void enabledBrokerRejectsShellMetacharacterCredentialRef() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "DOMAIN_SECURE_CHANNEL_VERIFY", 300, "vault:domain-ops/pilot;rm")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        EndpointDomainOpsRequest stored = onlyDomainOpsRequest();
        assertThat(stored.getState()).isEqualTo(DomainOpsStatus.DENIED);
        assertThat(stored.getReasonCode()).isEqualTo("credential-ref-invalid");
        assertThat(stored.getCredentialRef()).isNull();

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getMetadata())
                .containsEntry("credentialRefPresent", true)
                .containsEntry("credentialRefAccepted", false);
        assertThat(event.getMetadata().toString()).doesNotContain("vault:domain-ops/pilot;rm");
    }

    @Test
    void unavailableConnectorPersistsFailedResultAndRedactedCredentialCustody() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));

        var result = service.create(context(), device.getId(), request(
                "gpo-force-refresh", 600, CREDENTIAL_REF));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(result.operation()).isEqualTo("GPO_FORCE_REFRESH");
        assertThat(result.ttlSeconds()).isEqualTo(600);
        assertThat(result.reasonCode()).isEqualTo("connector-unavailable");
        assertThat(result.connectorName()).isEqualTo("unavailable");
        assertThat(result.expiresAt()).isNotNull();

        EndpointDomainOpsRequest stored = onlyDomainOpsRequest();
        assertThat(stored.getState()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(stored.getReasonCode()).isEqualTo("connector-unavailable");
        assertThat(stored.getCredentialRef()).isEqualTo(CREDENTIAL_REF);
        assertThat(stored.getCredentialRefHash()).hasSize(64);
        assertThat(stored.getConnectorName()).isEqualTo("unavailable");
        assertThat(stored.getCompletedAt()).isNotNull();

        List<EndpointAuditEvent> events = auditRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(EndpointAuditEvent::getEventType)
                .containsExactly(
                        DomainOpsBrokerService.EVENT_TYPE_REQUESTED,
                        DomainOpsBrokerService.EVENT_TYPE_FAILED);
        for (EndpointAuditEvent event : events) {
            assertThat(event.getMetadata())
                    .containsEntry("credentialRefPresent", true)
                    .containsEntry("credentialRefAccepted", true);
            assertThat((String) event.getMetadata().get("credentialRefHash")).hasSize(64);
            assertNoRawCredentialInAudit(event);
        }
    }

    @Test
    void typedConnectorDispatchCanReturnSucceededAttempt() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15), fakeConnector(
                DomainOpsStatus.SUCCEEDED,
                "secure-channel-verified",
                "attempt-001",
                Map.of("exitCode", 0, "signal", "redacted-ok")));

        var result = service.create(context(), device.getId(), request(
                "DOMAIN_SECURE_CHANNEL_VERIFY", 300, "delegated-worker:domain-ops/pilot"));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.SUCCEEDED);
        assertThat(result.reasonCode()).isEqualTo("secure-channel-verified");
        assertThat(result.connectorName()).isEqualTo("fake-domain-connector");
        assertThat(result.connectorAttemptId()).isEqualTo("attempt-001");

        EndpointDomainOpsRequest stored = onlyDomainOpsRequest();
        assertThat(stored.getState()).isEqualTo(DomainOpsStatus.SUCCEEDED);
        assertThat(stored.getConnectorAttemptId()).isEqualTo("attempt-001");
        assertThat(stored.getRedactedResult()).containsEntry("signal", "redacted-ok");
        assertThat(stored.getStateUpdatedAt()).isEqualTo(stored.getCompletedAt());

        assertThat(auditRepository.findAll()).extracting(EndpointAuditEvent::getEventType)
                .containsExactly(
                        DomainOpsBrokerService.EVENT_TYPE_REQUESTED,
                DomainOpsBrokerService.EVENT_TYPE_SUCCEEDED);
    }

    @Test
    void typedConnectorSeesCommittedAcceptedRequestBeforeDispatch() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15), new DomainOpsConnector() {
            @Override
            public String name() {
                return "commit-visible-connector";
            }

            @Override
            public DomainOpsConnectorDispatchResult dispatch(com.example.endpointadmin.domainops.DomainOpsConnectorDispatchRequest request) {
                return tx().execute(status -> {
                    EndpointDomainOpsRequest visible = domainOpsRequestRepository.findById(request.requestId())
                            .orElseThrow();
                    assertThat(visible.getState()).isEqualTo(DomainOpsStatus.ACCEPTED);
                    assertThat(visible.getCredentialRef()).isEqualTo(CREDENTIAL_REF);
                    assertThat(auditRepository.findAll()).extracting(EndpointAuditEvent::getEventType)
                            .containsExactly(DomainOpsBrokerService.EVENT_TYPE_REQUESTED);
                    return new DomainOpsConnectorDispatchResult(
                            DomainOpsStatus.SUCCEEDED,
                            "committed-before-dispatch",
                            "attempt-committed",
                            Map.of("visibility", "committed"));
                });
            }
        });

        var result = service.create(context(), device.getId(), request(
                "DOMAIN_SECURE_CHANNEL_VERIFY", 300, CREDENTIAL_REF));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.SUCCEEDED);
        assertThat(result.reasonCode()).isEqualTo("committed-before-dispatch");
        assertThat(auditRepository.findAll()).extracting(EndpointAuditEvent::getEventType)
                .containsExactly(
                        DomainOpsBrokerService.EVENT_TYPE_REQUESTED,
                        DomainOpsBrokerService.EVENT_TYPE_SUCCEEDED);
    }

    @Test
    void opaqueCredentialRefMayContainTokenPathSegment() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15), fakeConnector(
                DomainOpsStatus.SUCCEEDED,
                "ok",
                "attempt-token-path",
                Map.of("accepted", true)));

        var result = service.create(context(), device.getId(), request(
                "CERT_AUTOENROLL_PULSE", 300, "vault:domain-ops/token-renewer"));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.SUCCEEDED);
        EndpointDomainOpsRequest stored = onlyDomainOpsRequest();
        assertThat(stored.getCredentialRef()).isEqualTo("vault:domain-ops/token-renewer");
        assertThat(stored.getCredentialRefHash()).hasSize(64);
    }

    @Test
    void connectorReasonCodeFallsBackWhenSanitizedBlank() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15), fakeConnector(
                DomainOpsStatus.SUCCEEDED,
                ";",
                "attempt-bad-reason",
                Map.of("accepted", true)));

        var result = service.create(context(), device.getId(), request(
                "DOMAIN_SECURE_CHANNEL_VERIFY", 300, CREDENTIAL_REF));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.SUCCEEDED);
        assertThat(result.reasonCode()).isEqualTo("connector-dispatched");
        assertThat(onlyDomainOpsRequest().getReasonCode()).isEqualTo("connector-dispatched");
    }

    @Test
    void gpoMsiDeploymentPayloadIsValidatedStoredAndDispatched() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        Map<String, Object> payload = gpoMsiPayload();
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15), assertingConnector(request -> {
            assertThat(request.operation().name()).isEqualTo("ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT");
            assertThat(request.operationPayload())
                    .containsEntry("deploymentMethod", "gpo-msi")
                    .containsEntry("artifactVersion", "0.2.9")
                    .containsEntry("artifactSha256", "5cab18d460720e5bf89ddf0038f5b1c4d5ae04afc031dda0dc15d9810c969571")
                    .containsEntry("pilotOu", "OU=EndpointTest,DC=acik,DC=local")
                    .containsEntry("pilotGroup", "EndpointAgentPilotComputers")
                    .containsEntry("gpoName", "EndpointAgent Pilot - AGENTPC2")
                    .containsEntry("rollbackStrategy", "gpo-unlink-or-security-filter");
            assertThat(request.operationPayload().get("targetComputers"))
                    .isEqualTo(List.of("ERP-MOBIL", "SRB-AIDENETIMPC"));
            return new DomainOpsConnectorDispatchResult(
                    DomainOpsStatus.SUCCEEDED,
                    "gpo-msi-deployment-queued",
                    "attempt-gpo-msi",
                    Map.of("connectorSignal", "queued"));
        }));

        var result = service.create(context(), device.getId(), request(
                "ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT", 300, CREDENTIAL_REF, payload));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.SUCCEEDED);
        EndpointDomainOpsRequest stored = onlyDomainOpsRequest();
        assertThat(stored.getOperationPayload()).containsEntry("deploymentMethod", "gpo-msi");
        assertThat(stored.getOperationPayload().toString())
                .doesNotContain("password")
                .doesNotContain("powershell")
                .doesNotContain("cmd");

        List<EndpointAuditEvent> events = auditRepository.findAll();
        assertThat(events).hasSize(2);
        for (EndpointAuditEvent event : events) {
            assertThat(event.getMetadata())
                    .containsEntry("operationPayloadPresent", true)
                    .containsEntry("operationPayloadFieldCount", 9);
            assertThat(event.getMetadata().toString())
                    .doesNotContain("EndpointAgent-0.2.9-signed.msi")
                    .doesNotContain("5cab18d460720e5bf89ddf0038f5b1c4d5ae04afc031dda0dc15d9810c969571");
        }
    }

    @Test
    void rolloutCollectorPayloadIsValidatedStoredAndDispatched() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        Map<String, Object> payload = Map.of(
                "expectedApiHost", "mtls.testai.acik.com",
                "expectedMsiSha256", "5CAB18D460720E5BF89DDf0038F5B1C4D5AE04AFC031DDA0DC15D9810C969571",
                "targetComputers", List.of("AgentPc2"),
                "includeGpResultHtml", true,
                "includeServiceStatus", true,
                "includeAgentLogTail", true,
                "restartServiceBeforeCollect", false);
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15), assertingConnector(request -> {
            assertThat(request.operation().name()).isEqualTo("ENDPOINT_AGENT_ROLLOUT_COLLECTOR");
            assertThat(request.operationPayload())
                    .containsEntry("expectedApiHost", "mtls.testai.acik.com")
                    .containsEntry("expectedMsiSha256", "5cab18d460720e5bf89ddf0038f5b1c4d5ae04afc031dda0dc15d9810c969571")
                    .containsEntry("includeGpResultHtml", true)
                    .containsEntry("restartServiceBeforeCollect", false);
            assertThat(request.operationPayload().get("targetComputers"))
                    .isEqualTo(List.of("AGENTPC2"));
            return new DomainOpsConnectorDispatchResult(
                    DomainOpsStatus.SUCCEEDED,
                    "collector-queued",
                    "attempt-collector",
                    Map.of("collectorSignal", "queued"));
        }));

        var result = service.create(context(), device.getId(), request(
                "endpoint-agent-rollout-collector", 300, CREDENTIAL_REF, payload));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.SUCCEEDED);
        assertThat(onlyDomainOpsRequest().getOperationPayload())
                .containsEntry("expectedApiHost", "mtls.testai.acik.com");
    }

    @Test
    void payloadRejectsRawCommandAndCredentialLikeFieldsWithoutDurableRequest() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15), fakeConnector(
                DomainOpsStatus.SUCCEEDED,
                "must-not-run",
                "attempt",
                Map.of()));
        Map<String, Object> payload = new java.util.LinkedHashMap<>(gpoMsiPayload());
        payload.put("script", "powershell -EncodedCommand AAAA");

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT", 300, CREDENTIAL_REF, payload)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST)
                .extracting("reason")
                .asString()
                .doesNotContain("script")
                .doesNotContain("powershell")
                .doesNotContain("EncodedCommand");

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getEventType()).isEqualTo(DomainOpsBrokerService.EVENT_TYPE_DENIED);
        assertThat(event.getMetadata())
                .containsEntry("reasonCode", "payload-invalid")
                .containsEntry("operationPayloadPresent", true)
                .containsEntry("operationPayloadFieldCount", 10);
        assertThat(event.getMetadata().toString()).doesNotContain("EncodedCommand");
        assertThat(domainOpsRequestRepository.findAll()).isEmpty();
    }

    @Test
    void legacyDomainOpsRejectNonEmptyPayload() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "GPO_FORCE_REFRESH", 300, CREDENTIAL_REF, Map.of("targetComputers", List.of("PC1")))))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getMetadata())
                .containsEntry("reasonCode", "payload-invalid")
                .containsEntry("operation", "GPO_FORCE_REFRESH")
                .containsEntry("operationPayloadPresent", true)
                .containsEntry("operationPayloadFieldCount", 1);
        assertThat(domainOpsRequestRepository.findAll()).isEmpty();
    }

    @Test
    void idempotencyReplayReturnsExistingDurableRequestWithoutNewAudit() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));
        String idempotencyKey = "domops-stable-" + UUID.randomUUID();

        var first = service.create(context(), device.getId(), request(
                "CERT_AUTOENROLL_PULSE", 300, CREDENTIAL_REF, idempotencyKey));
        var second = service.create(context(), device.getId(), request(
                "CERT_AUTOENROLL_PULSE", 300, "vault:domain-ops/other", idempotencyKey));

        assertThat(second.operationId()).isEqualTo(first.operationId());
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(domainOpsRequestRepository.findAll()).hasSize(1);
        assertThat(auditRepository.findAll()).hasSize(2);
    }

    @Test
    void idempotencyConstraintRaceReplaysExistingDurableRequestWithoutRawDbError() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        EndpointDomainOpsRequest existing = storedDomainOpsRequest(
                device,
                DomainOpsStatus.FAILED,
                clock.instant().minusSeconds(60),
                clock.instant().plusSeconds(240));
        existing.markConnectorResult(
                DomainOpsStatus.FAILED,
                "connector-unavailable",
                "unavailable",
                null,
                Map.of(),
                clock.instant().minusSeconds(30));

        EndpointDeviceRepository devices = mock(EndpointDeviceRepository.class);
        EndpointDomainOpsRequestRepository requests = mock(EndpointDomainOpsRequestRepository.class);
        EndpointAuditService audits = mock(EndpointAuditService.class);
        when(devices.findVisibleToOrgAndId(TENANT, device.getId())).thenReturn(Optional.of(device));
        when(requests.findByTenantIdAndIdempotencyKeyHash(eq(TENANT), anyString()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(requests.saveAndFlush(any(EndpointDomainOpsRequest.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        DomainOpsBrokerService service = new DomainOpsBrokerService(
                devices,
                requests,
                audits,
                clock,
                noOpTransactionManager(),
                fakeConnector(DomainOpsStatus.SUCCEEDED, "should-not-dispatch", "attempt", Map.of()),
                true,
                Duration.ofMinutes(15));

        var result = service.create(context(), device.getId(), request(
                "CERT_AUTOENROLL_PULSE", 300, CREDENTIAL_REF, "same-idempotency-key"));

        assertThat(result.operationId()).isEqualTo(existing.getId());
        assertThat(result.status()).isEqualTo(DomainOpsStatus.FAILED);
        assertThat(result.reasonCode()).isEqualTo("connector-unavailable");
        verify(requests).saveAndFlush(any(EndpointDomainOpsRequest.class));
    }

    @Test
    void reconcilerExpiresStaleAcceptedAndPendingDispatchRowsWithAudit() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        EndpointDomainOpsRequest accepted = domainOpsRequestRepository.saveAndFlush(storedDomainOpsRequest(
                device,
                DomainOpsStatus.ACCEPTED,
                clock.instant().minusSeconds(600),
                clock.instant().minusSeconds(60)));
        EndpointDomainOpsRequest pending = domainOpsRequestRepository.saveAndFlush(storedDomainOpsRequest(
                device,
                DomainOpsStatus.PENDING_DISPATCH,
                clock.instant().minusSeconds(500),
                clock.instant().minusSeconds(30)));
        EndpointDomainOpsRequest fresh = domainOpsRequestRepository.saveAndFlush(storedDomainOpsRequest(
                device,
                DomainOpsStatus.ACCEPTED,
                clock.instant().minusSeconds(60),
                clock.instant().plusSeconds(300)));

        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));

        int expired = service.expireStaleDispatchWindows(10);

        assertThat(expired).isEqualTo(2);
        assertThat(domainOpsRequestRepository.findById(accepted.getId()).orElseThrow().getState())
                .isEqualTo(DomainOpsStatus.EXPIRED);
        assertThat(domainOpsRequestRepository.findById(pending.getId()).orElseThrow().getState())
                .isEqualTo(DomainOpsStatus.EXPIRED);
        assertThat(domainOpsRequestRepository.findById(fresh.getId()).orElseThrow().getState())
                .isEqualTo(DomainOpsStatus.ACCEPTED);

        List<EndpointAuditEvent> events = auditRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(EndpointAuditEvent::getEventType)
                .containsOnly(DomainOpsBrokerService.EVENT_TYPE_EXPIRED);
        for (EndpointAuditEvent event : events) {
            assertThat(event.getMetadata())
                    .containsEntry("status", DomainOpsStatus.EXPIRED.name())
                    .containsEntry("reasonCode", "dispatch-window-expired")
                    .containsEntry("credentialRefPresent", true)
                    .containsEntry("credentialRefAccepted", true);
            assertNoRawCredentialInAudit(event);
        }
    }

    @Test
    void ttlOverFifteenMinutesIsDeniedEvenWhenConfiguredHigher() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofHours(2));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "CERT_AUTOENROLL_PULSE", 901, CREDENTIAL_REF)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getEventType()).isEqualTo(DomainOpsBrokerService.EVENT_TYPE_DENIED);
        assertThat(event.getMetadata())
                .containsEntry("reasonCode", "ttl-exceeds-max");
        assertThat(event.getMetadata().get("maxPermitTtlSeconds").toString()).isEqualTo("900");
        assertThat(domainOpsRequestRepository.findAll()).isEmpty();
    }

    @Test
    void unsupportedOperationIsDeniedBeforeAnyDispatchClaim() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "AD_USER_PASSWORD_RESET", 300, CREDENTIAL_REF)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getEventType()).isEqualTo(DomainOpsBrokerService.EVENT_TYPE_DENIED);
        assertThat(event.getMetadata())
                .containsEntry("operation", "AD_USER_PASSWORD_RESET")
                .containsEntry("reasonCode", "unsupported-operation");
        assertThat(domainOpsRequestRepository.findAll()).isEmpty();
    }

    @Test
    void crossTenantDeviceDoesNotLeakExistenceOrWriteDeviceAudit() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));
        AdminTenantContext otherTenant = new AdminTenantContext(
                UUID.fromString("88888888-8888-8888-8888-888888888888"),
                SUBJECT);

        assertThatThrownBy(() -> service.create(otherTenant, device.getId(), request(
                "DOMAIN_SECURE_CHANNEL_VERIFY", 300, CREDENTIAL_REF)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);

        assertThat(auditRepository.findAll()).isEmpty();
        assertThat(domainOpsRequestRepository.findAll()).isEmpty();
    }

    private DomainOpsBrokerService service(boolean enabled, Duration maxTtl) {
        return new DomainOpsBrokerService(
                deviceRepository,
                domainOpsRequestRepository,
                auditService,
                clock,
                transactionManager,
                enabled,
                maxTtl);
    }

    private DomainOpsBrokerService service(boolean enabled,
                                           Duration maxTtl,
                                           DomainOpsConnector connector) {
        return new DomainOpsBrokerService(
                deviceRepository,
                domainOpsRequestRepository,
                auditService,
                clock,
                transactionManager,
                connector,
                enabled,
                maxTtl);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private DomainOpsConnector fakeConnector(DomainOpsStatus status,
                                             String reasonCode,
                                             String attemptId,
                                             Map<String, Object> redactedResult) {
        return new DomainOpsConnector() {
            @Override
            public String name() {
                return "fake-domain-connector";
            }

            @Override
            public DomainOpsConnectorDispatchResult dispatch(com.example.endpointadmin.domainops.DomainOpsConnectorDispatchRequest request) {
                assertThat(request.credentialRef()).isNotBlank();
                assertThat(request.expiresAt()).isNotNull();
                return new DomainOpsConnectorDispatchResult(status, reasonCode, attemptId, redactedResult);
            }
        };
    }

    private DomainOpsConnector assertingConnector(java.util.function.Function<DomainOpsConnectorDispatchRequest,
            DomainOpsConnectorDispatchResult> dispatcher) {
        return new DomainOpsConnector() {
            @Override
            public String name() {
                return "asserting-domain-connector";
            }

            @Override
            public DomainOpsConnectorDispatchResult dispatch(DomainOpsConnectorDispatchRequest request) {
                assertThat(request.credentialRef()).isNotBlank();
                assertThat(request.expiresAt()).isNotNull();
                return dispatcher.apply(request);
            }
        };
    }

    private AdminTenantContext context() {
        return new AdminTenantContext(TENANT, SUBJECT);
    }

    private CreateDomainOpsRequest request(String operation, long ttlSeconds, String credentialRef) {
        return request(operation, ttlSeconds, credentialRef, "domops-" + UUID.randomUUID());
    }

    private CreateDomainOpsRequest request(String operation,
                                           long ttlSeconds,
                                           String credentialRef,
                                           Map<String, Object> payload) {
        return request(operation, ttlSeconds, credentialRef, "domops-" + UUID.randomUUID(), payload);
    }

    private CreateDomainOpsRequest request(String operation,
                                           long ttlSeconds,
                                           String credentialRef,
                                           String idempotencyKey) {
        return request(operation, ttlSeconds, credentialRef, idempotencyKey, null);
    }

    private CreateDomainOpsRequest request(String operation,
                                           long ttlSeconds,
                                           String credentialRef,
                                           String idempotencyKey,
                                           Map<String, Object> payload) {
        return new CreateDomainOpsRequest(
                operation,
                "pilot validation",
                ttlSeconds,
                idempotencyKey,
                credentialRef,
                payload);
    }

    private Map<String, Object> gpoMsiPayload() {
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

    private EndpointDevice persistDevice(UUID tenantId, String hostname) {
        return deviceRepository.saveAndFlush(deviceOnly(tenantId, hostname));
    }

    private EndpointDevice deviceOnly(UUID tenantId, String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        device.setOrgId(tenantId);
        device.setHostname(hostname + "-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("Windows 11");
        device.setAgentVersion("0.3.0");
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setDomainName("acik.local");
        device.setStatus(DeviceStatus.ONLINE);
        return device;
    }

    private EndpointDomainOpsRequest storedDomainOpsRequest(EndpointDevice device,
                                                            DomainOpsStatus state,
                                                            Instant requestedAt,
                                                            Instant expiresAt) {
        EndpointDomainOpsRequest request = new EndpointDomainOpsRequest();
        request.setId(UUID.randomUUID());
        request.setTenantId(device.getTenantId());
        request.setOrgId(device.getTenantId());
        request.setDeviceId(device.getId());
        request.setOperation(com.example.endpointadmin.domainops.DomainOpsOperation.CERT_AUTOENROLL_PULSE);
        request.setState(state);
        request.setReason("pilot validation");
        request.setReasonCode(state == DomainOpsStatus.PENDING_DISPATCH ? "pending-dispatch" : "accepted");
        request.setIdempotencyKeyHash("a".repeat(63) + UUID.randomUUID().toString().substring(0, 1));
        request.setCredentialRef(CREDENTIAL_REF);
        request.setCredentialRefHash("b".repeat(64));
        request.setOperationPayload(Map.of());
        request.setRequestedBy(SUBJECT);
        request.setTtlSeconds(Duration.between(requestedAt, expiresAt).toSeconds());
        request.setRequestedAt(requestedAt);
        request.setExpiresAt(expiresAt);
        request.setStateUpdatedAt(requestedAt);
        request.setRedactedResult(Map.of());
        return request;
    }

    private PlatformTransactionManager noOpTransactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }

    private EndpointAuditEvent onlyAuditEvent() {
        List<EndpointAuditEvent> events = auditRepository.findAll();
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private EndpointDomainOpsRequest onlyDomainOpsRequest() {
        List<EndpointDomainOpsRequest> requests = domainOpsRequestRepository.findAll();
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    private void assertNoRawCredentialInAudit(EndpointAuditEvent event) {
        assertThat(event.getMetadata().keySet())
                .doesNotContain("commandLine", "rawCommand", "password", "credential", "credentialRef");
        assertThat(event.getMetadata().toString()).doesNotContain(CREDENTIAL_REF);
    }
}
