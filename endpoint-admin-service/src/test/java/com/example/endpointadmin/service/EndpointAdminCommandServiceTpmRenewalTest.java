package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateTpmCertificateRenewalRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeploymentRing;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.repository.EndpointAgentUpdateReleaseRepository;
import com.example.endpointadmin.repository.EndpointCommandApprovalRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointAdminCommandServiceTpmRenewalTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ENROLLMENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID COMMAND_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-07-26T15:00:00Z");
    private static final AdminTenantContext CONTEXT =
            new AdminTenantContext(TENANT_ID, "platform-admin");

    @Mock private EndpointCommandRepository commandRepository;
    @Mock private EndpointCommandResultRepository resultRepository;
    @Mock private EndpointCommandApprovalRepository approvalRepository;
    @Mock private EndpointDeviceRepository deviceRepository;
    @Mock private EndpointSoftwareCatalogItemRepository catalogRepository;
    @Mock private EndpointAgentUpdateReleaseRepository agentUpdateReleaseRepository;
    @Mock private EndpointHeartbeatRepository heartbeatRepository;
    @Mock private EndpointInstallPreflightService preflightService;
    @Mock private EndpointCommandSecretService commandSecretService;
    @Mock private EndpointEnrollmentService enrollmentService;
    @Mock private EndpointAuditService auditService;

    private EndpointAdminCommandService service;

    @BeforeEach
    void setUp() {
        service = new EndpointAdminCommandService(
                commandRepository,
                resultRepository,
                approvalRepository,
                deviceRepository,
                catalogRepository,
                agentUpdateReleaseRepository,
                heartbeatRepository,
                preflightService,
                commandSecretService,
                enrollmentService,
                auditService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(5),
                Set.of(CommandType.COLLECT_INVENTORY),
                event -> { });
    }

    @Test
    void createsDeviceBoundRenewalWithoutTokenInCommandPayload() {
        EndpointDevice device = device();
        Instant expiresAt = NOW.plus(Duration.ofMinutes(15));
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(device));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(heartbeat()));
        when(commandRepository.findByTenantIdAndIdempotencyKey(
                TENANT_ID, "admin-renew-tpm:" + DEVICE_ID + ":browser-1"))
                .thenReturn(Optional.empty());
        when(enrollmentService.issueDeviceBoundEnrollment(
                CONTEXT, device, "browser-managed TPM certificate renewal", Duration.ofMinutes(15)))
                .thenReturn(new EndpointEnrollmentService.IssuedEnrollmentToken(
                        ENROLLMENT_ID, "A".repeat(43), expiresAt, DEVICE_ID));
        when(commandRepository.saveAndFlush(any(EndpointCommand.class))).thenAnswer(invocation -> {
            EndpointCommand command = invocation.getArgument(0);
            setField(command, "id", COMMAND_ID);
            return command;
        });
        when(resultRepository.findByCommand_Id(COMMAND_ID)).thenReturn(Optional.empty());

        EndpointCommandDto dto = service.createTpmCertificateRenewal(
                CONTEXT,
                DEVICE_ID,
                new CreateTpmCertificateRenewalRequest("browser-1", "certificate rotation"));

        assertThat(dto.type()).isEqualTo(CommandType.RENEW_TPM_CERTIFICATE);
        assertThat(dto.status()).isEqualTo(CommandStatus.QUEUED);
        assertThat(dto.approvalStatus()).isEqualTo(ApprovalStatus.NOT_REQUIRED);
        assertThat(dto.payload())
                .containsEntry("enrollmentId", ENROLLMENT_ID.toString())
                .containsEntry("secretRef", "endpoint-command-secret:enrollmentToken")
                .doesNotContainKey("enrollmentToken");
        verify(commandSecretService).createTpmEnrollmentTokenSecret(
                eq(TENANT_ID), eq(device), any(EndpointCommand.class), eq("A".repeat(43)),
                eq(expiresAt), eq("platform-admin"), eq(ENROLLMENT_ID), eq("certificate rotation"));
    }

    @Test
    void rejectsWhenLatestHeartbeatDoesNotAdvertiseCapability() {
        EndpointDevice device = device();
        when(deviceRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, DEVICE_ID))
                .thenReturn(Optional.of(device));
        EndpointHeartbeat heartbeat = heartbeat();
        heartbeat.setPayload(Map.of("capabilities", List.of("UPDATE_AGENT")));
        when(heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(DEVICE_ID))
                .thenReturn(Optional.of(heartbeat));

        assertThatThrownBy(() -> service.createTpmCertificateRenewal(
                CONTEXT,
                DEVICE_ID,
                new CreateTpmCertificateRenewalRequest("browser-1", "certificate rotation")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RENEW_TPM_CERTIFICATE");
    }

    private EndpointDevice device() {
        EndpointDevice device = new EndpointDevice();
        setField(device, "id", DEVICE_ID);
        device.setTenantId(TENANT_ID);
        device.setHostname("MKR-A1");
        device.setDeploymentRing(DeploymentRing.PILOT);
        return device;
    }

    private EndpointHeartbeat heartbeat() {
        EndpointHeartbeat heartbeat = new EndpointHeartbeat();
        heartbeat.setReceivedAt(NOW.minusSeconds(30));
        heartbeat.setPayload(Map.of(
                "capabilities", List.of("UPDATE_AGENT", "RENEW_TPM_CERTIFICATE")));
        return heartbeat;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
