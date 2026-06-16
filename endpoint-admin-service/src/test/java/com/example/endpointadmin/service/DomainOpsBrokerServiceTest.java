package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.domainops.DomainOpsStatus;
import com.example.endpointadmin.dto.v1.admin.CreateDomainOpsRequest;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointAuditService.class,
        NoOpAuditChainLock.class
})
class DomainOpsBrokerServiceTest {

    private static final UUID TENANT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String SUBJECT = "admin@example.com";

    @Autowired private EndpointDeviceRepository deviceRepository;
    @Autowired private EndpointAuditEventRepository auditRepository;
    @Autowired private EndpointAuditService auditService;
    @Autowired private Clock clock;

    @Test
    void disabledBrokerDeniesAndPersistsAudit() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(false, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "DOMAIN_SECURE_CHANNEL_VERIFY", 300)))
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
                .containsEntry("ttlSeconds", 300L);
        assertThat(event.getMetadata().keySet())
                .doesNotContain("commandLine", "rawCommand", "password", "credential");
    }

    @Test
    void enabledBrokerAcceptsSafePilotOperationAsPendingDispatch() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));

        var result = service.create(context(), device.getId(), request(
                "gpo-force-refresh", 600));

        assertThat(result.status()).isEqualTo(DomainOpsStatus.PENDING_DISPATCH);
        assertThat(result.operation()).isEqualTo("GPO_FORCE_REFRESH");
        assertThat(result.ttlSeconds()).isEqualTo(600);
        assertThat(result.reasonCode()).isEqualTo("awaiting-domain-connector");

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getEventType()).isEqualTo(DomainOpsBrokerService.EVENT_TYPE_REQUESTED);
        assertThat(event.getAction()).isEqualTo("domain-ops.requested");
        assertThat(event.getMetadata())
                .containsEntry("operation", "GPO_FORCE_REFRESH")
                .containsEntry("status", DomainOpsStatus.PENDING_DISPATCH.name())
                .containsEntry("ttlSeconds", 600L)
                .containsEntry("contract", "agent-198:max-permit-ttl-15m,mtls-only,no-raw-shell");
        assertThat((String) event.getMetadata().get("idempotencyKeyHash")).hasSize(64);
    }

    @Test
    void ttlOverFifteenMinutesIsDeniedEvenWhenConfiguredHigher() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofHours(2));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "CERT_AUTOENROLL_PULSE", 901)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getEventType()).isEqualTo(DomainOpsBrokerService.EVENT_TYPE_DENIED);
        assertThat(event.getMetadata())
                .containsEntry("reasonCode", "ttl-exceeds-max")
                .containsEntry("maxPermitTtlSeconds", 900L);
    }

    @Test
    void unsupportedOperationIsDeniedBeforeAnyDispatchClaim() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.create(context(), device.getId(), request(
                "AD_USER_PASSWORD_RESET", 300)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

        EndpointAuditEvent event = onlyAuditEvent();
        assertThat(event.getEventType()).isEqualTo(DomainOpsBrokerService.EVENT_TYPE_DENIED);
        assertThat(event.getMetadata())
                .containsEntry("operation", "AD_USER_PASSWORD_RESET")
                .containsEntry("reasonCode", "unsupported-operation");
    }

    @Test
    void crossTenantDeviceDoesNotLeakExistenceOrWriteDeviceAudit() {
        EndpointDevice device = persistDevice(TENANT, "PC-DOMOPS");
        DomainOpsBrokerService service = service(true, Duration.ofMinutes(15));
        AdminTenantContext otherTenant = new AdminTenantContext(
                UUID.fromString("88888888-8888-8888-8888-888888888888"),
                SUBJECT);

        assertThatThrownBy(() -> service.create(otherTenant, device.getId(), request(
                "DOMAIN_SECURE_CHANNEL_VERIFY", 300)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);

        assertThat(auditRepository.findAll()).isEmpty();
    }

    private DomainOpsBrokerService service(boolean enabled, Duration maxTtl) {
        return new DomainOpsBrokerService(deviceRepository, auditService, clock, enabled, maxTtl);
    }

    private AdminTenantContext context() {
        return new AdminTenantContext(TENANT, SUBJECT);
    }

    private CreateDomainOpsRequest request(String operation, long ttlSeconds) {
        return new CreateDomainOpsRequest(operation, "pilot validation", ttlSeconds, "domops-" + UUID.randomUUID());
    }

    private EndpointDevice persistDevice(UUID tenantId, String hostname) {
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
        return deviceRepository.saveAndFlush(device);
    }

    private EndpointAuditEvent onlyAuditEvent() {
        List<EndpointAuditEvent> events = auditRepository.findAll();
        assertThat(events).hasSize(1);
        return events.get(0);
    }
}
