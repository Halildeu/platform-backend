package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.AdminBackupDryrunRequestApproval;
import com.example.endpointadmin.dto.v1.admin.AdminBackupDryrunRequestCreate;
import com.example.endpointadmin.dto.v1.admin.AdminBackupDryrunRequestResponse;
import com.example.endpointadmin.dto.v1.admin.AdminManagedRootCreateRequest;
import com.example.endpointadmin.model.BackupDryrunRequestState;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointBackupDryrunManagedRoot;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointBackupDryrunManagedRootRepository;
import com.example.endpointadmin.repository.EndpointBackupDryrunRequestRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.8A.3b (#648) — backup dry-run issuing surface tests (propose→approve
 * dual-control). H2 slice; feature + per-tenant enabled via @TestPropertySource.
 * The 503/per-tenant fail-closed gate is shared with the registry service and
 * covered in EndpointBackupDryrunRegistryServiceTest.
 */
@IsolatedH2DataJpaTest
@Import({
        TimeConfig.class,
        EndpointBackupDryrunService.class,
        EndpointBackupDryrunRegistryService.class,
        EndpointAuditService.class,
        NoOpAuditChainLock.class
})
@TestPropertySource(properties = {
        "endpoint-admin.backup-dryrun.enabled=true",
        "endpoint-admin.backup-dryrun.allowed-tenant-ids=66666666-6666-6666-6666-666666666666",
        "endpoint-admin.backup-dryrun.heartbeat-freshness-ttl=PT5M",
        "endpoint-admin.backup-dryrun.required-capability=COLLECT_BACKUP_DRYRUN",
        "endpoint-admin.backup-dryrun.command-ttl=PT30M",
        "endpoint-admin.backup-dryrun.max-roots=32"
})
class EndpointBackupDryrunServiceTest {

    private static final UUID TENANT = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final String ROOT_REF = "managed_root:77777777-7777-7777-7777-777777777777";

    @Autowired private EndpointBackupDryrunService service;
    @Autowired private EndpointBackupDryrunRegistryService registryService;
    @Autowired private EndpointDeviceRepository deviceRepository;
    @Autowired private EndpointHeartbeatRepository heartbeatRepository;
    @Autowired private EndpointCommandRepository commandRepository;
    @Autowired private EndpointBackupDryrunRequestRepository requestRepository;
    @Autowired private EndpointBackupDryrunManagedRootRepository rootRepository;

    private AdminTenantContext alice() {
        return new AdminTenantContext(TENANT, ALICE);
    }

    private AdminTenantContext bob() {
        return new AdminTenantContext(TENANT, BOB);
    }

    private void registerRoot() {
        registryService.register(alice(),
                new AdminManagedRootCreateRequest(ROOT_REF, "managed/it-folder",
                        "C:\\Users\\Acme\\OneDrive - Acme\\Shared", true));
    }

    private UUID seedDevice() {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(TENANT);
        device.setHostname("PC-BDR-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("Windows 11");
        device.setAgentVersion("0.3.0");
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setDomainName("corp.local");
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastSeenAt(Instant.now());
        return deviceRepository.saveAndFlush(device).getId();
    }

    private void seedHeartbeat(UUID deviceId, List<String> capabilities) {
        EndpointDevice device = deviceRepository.findById(deviceId).orElseThrow();
        EndpointHeartbeat hb = new EndpointHeartbeat();
        hb.setTenantId(TENANT);
        hb.setDevice(device);
        hb.setReceivedAt(Instant.now());
        hb.setAgentVersion("0.3.0");
        hb.setPayload(new HashMap<>(Map.of("capabilities", capabilities)));
        heartbeatRepository.saveAndFlush(hb);
    }

    private AdminBackupDryrunRequestCreate createReq() {
        return new AdminBackupDryrunRequestCreate(List.of(ROOT_REF), "profile-1", "quarterly backup eligibility", null);
    }

    // ---- happy path -------------------------------------------------------

    @Test
    void proposeThenApproveDispatchesCommand() {
        registerRoot();
        UUID deviceId = seedDevice();
        seedHeartbeat(deviceId, List.of("COLLECT_BACKUP_DRYRUN"));

        AdminBackupDryrunRequestResponse proposed = service.propose(alice(), deviceId, createReq());
        assertThat(proposed.state()).isEqualTo(BackupDryrunRequestState.PENDING_APPROVAL);
        assertThat(proposed.rootRefs()).containsExactly(ROOT_REF);

        AdminBackupDryrunRequestResponse approved =
                service.approve(bob(), deviceId, proposed.id(), new AdminBackupDryrunRequestApproval(null));
        assertThat(approved.state()).isEqualTo(BackupDryrunRequestState.APPROVED);
        assertThat(approved.commandId()).isNotNull();

        EndpointCommand cmd = commandRepository.findById(approved.commandId()).orElseThrow();
        assertThat(cmd.getCommandType()).isEqualTo(CommandType.COLLECT_BACKUP_DRYRUN);
        assertThat(cmd.getExpiresAt()).isNotNull(); // bounded TTL, never null
        // dispatch payload resolves the raw local_path (only here)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roots = (List<Map<String, Object>>) cmd.getPayload().get("roots");
        assertThat(roots).hasSize(1);
        assertThat(roots.get(0).get("local_path")).isEqualTo("C:\\Users\\Acme\\OneDrive - Acme\\Shared");
        assertThat(roots.get(0).get("root_ref")).isEqualTo(ROOT_REF);
    }

    // ---- dual-control + gates --------------------------------------------

    @Test
    void makerCheckerSameSubjectRejected() {
        registerRoot();
        UUID deviceId = seedDevice();
        seedHeartbeat(deviceId, List.of("COLLECT_BACKUP_DRYRUN"));
        AdminBackupDryrunRequestResponse proposed = service.propose(alice(), deviceId, createReq());
        // alice approving her own proposal → 403
        assertThatThrownBy(() -> service.approve(alice(), deviceId, proposed.id(),
                new AdminBackupDryrunRequestApproval(null)))
                .isInstanceOf(EndpointBackupDryrunMakerCheckerViolationException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
    }

    @Test
    void registryDriftAtApproveRejected() {
        registerRoot();
        UUID deviceId = seedDevice();
        seedHeartbeat(deviceId, List.of("COLLECT_BACKUP_DRYRUN"));
        AdminBackupDryrunRequestResponse proposed = service.propose(alice(), deviceId, createReq());

        // bump the root's version after propose → approve must detect drift.
        EndpointBackupDryrunManagedRoot root =
                rootRepository.findByTenantIdAndRootRef(TENANT, ROOT_REF).orElseThrow();
        root.setRootVersion(root.getRootVersion() + 1);
        rootRepository.saveAndFlush(root);

        assertThatThrownBy(() -> service.approve(bob(), deviceId, proposed.id(),
                new AdminBackupDryrunRequestApproval(null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
    }

    @Test
    void capabilityMissingAtApproveRejected() {
        registerRoot();
        UUID deviceId = seedDevice();
        seedHeartbeat(deviceId, List.of("INSTALL_SOFTWARE")); // no COLLECT_BACKUP_DRYRUN
        AdminBackupDryrunRequestResponse proposed = service.propose(alice(), deviceId, createReq());
        assertThatThrownBy(() -> service.approve(bob(), deviceId, proposed.id(),
                new AdminBackupDryrunRequestApproval(null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void unknownRootRefAtProposeRejected() {
        // root not registered → 422
        UUID deviceId = seedDevice();
        assertThatThrownBy(() -> service.propose(alice(), deviceId, createReq()))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void reasonRequiredAtPropose() {
        registerRoot();
        UUID deviceId = seedDevice();
        assertThatThrownBy(() -> service.propose(alice(), deviceId,
                new AdminBackupDryrunRequestCreate(List.of(ROOT_REF), "profile-1", "   ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
    }

    @Test
    void reasonWithRawPathRejected() {
        registerRoot();
        UUID deviceId = seedDevice();
        assertThatThrownBy(() -> service.propose(alice(), deviceId,
                new AdminBackupDryrunRequestCreate(List.of(ROOT_REF), "profile-1",
                        "scan C:\\Users\\Alice please", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
    }

    @Test
    void singleFlightSecondProposeRejected() {
        registerRoot();
        UUID deviceId = seedDevice();
        seedHeartbeat(deviceId, List.of("COLLECT_BACKUP_DRYRUN"));
        service.propose(alice(), deviceId, createReq());
        // a second OPEN propose for the same device → 409 (use a distinct idempotency key)
        assertThatThrownBy(() -> service.propose(alice(), deviceId,
                new AdminBackupDryrunRequestCreate(List.of(ROOT_REF), "profile-1", "second attempt", "distinct-key-2")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
    }

    @Test
    void idempotentProposeReturnsSameRequest() {
        registerRoot();
        UUID deviceId = seedDevice();
        seedHeartbeat(deviceId, List.of("COLLECT_BACKUP_DRYRUN"));
        AdminBackupDryrunRequestCreate req =
                new AdminBackupDryrunRequestCreate(List.of(ROOT_REF), "profile-1", "first", "same-key");
        AdminBackupDryrunRequestResponse first = service.propose(alice(), deviceId, req);
        AdminBackupDryrunRequestResponse replay = service.propose(alice(), deviceId, req);
        assertThat(replay.id()).isEqualTo(first.id());
    }

    @Test
    void activeApprovedDryRunBlocksNewPropose() {
        // Codex 019ec45e #3: single-flight = one ACTIVE dry-run per device. After
        // approve the command is QUEUED (non-terminal) → a new propose is 409.
        registerRoot();
        UUID deviceId = seedDevice();
        seedHeartbeat(deviceId, List.of("COLLECT_BACKUP_DRYRUN"));
        AdminBackupDryrunRequestResponse proposed = service.propose(alice(), deviceId, createReq());
        service.approve(bob(), deviceId, proposed.id(), new AdminBackupDryrunRequestApproval(null));

        assertThatThrownBy(() -> service.propose(alice(), deviceId,
                new AdminBackupDryrunRequestCreate(List.of(ROOT_REF), "profile-1", "second attempt", "key-2")))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
    }
}
