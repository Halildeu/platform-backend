package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminBackupDryrunRequestApproval;
import com.example.endpointadmin.dto.v1.admin.AdminBackupDryrunRequestCreate;
import com.example.endpointadmin.dto.v1.admin.AdminBackupDryrunRequestResponse;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.BackupDryrunRequestState;
import com.example.endpointadmin.model.BackupDryrunRootSnapshot;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointBackupDryrunManagedRoot;
import com.example.endpointadmin.model.EndpointBackupDryrunRequest;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.repository.EndpointBackupDryrunManagedRootRepository;
import com.example.endpointadmin.repository.EndpointBackupDryrunRequestRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Faz 22.8A.3b (#648) — dedicated backup dry-run ISSUING surface
 * (propose→approve dual-control). Clone of the UNINSTALL AG-028 Phase 1b
 * maker-checker, adapted to the registry-first/path-free backup dry-run
 * (Codex 019ec45e). Consumes the 22.8A.3a registry by OPAQUE root_ref; the raw
 * local_path is resolved ONLY into the dispatch command payload (which the
 * generic command DTO redacts). Disabled-by-default + per-tenant opt-in.
 */
@Service
public class EndpointBackupDryrunService {

    private static final String HEARTBEAT_CAPABILITIES_KEY = "capabilities";
    private static final Pattern DRIVE_PATH = Pattern.compile("(^|[^A-Za-z])[A-Za-z]:");
    private static final Pattern PROFILE_OPAQUE = Pattern.compile("^[A-Za-z0-9._:-]+$");
    // idempotency_key flows to the request row AND the audit correlation_id
    // (length=128) — opaque + path-free + ≤128 (Codex 019ec45e P1).
    private static final Pattern IDEMPOTENCY_KEY_OPAQUE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final EndpointBackupDryrunRequestRepository requestRepository;
    private final EndpointBackupDryrunManagedRootRepository rootRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final EndpointCommandRepository commandRepository;
    private final EndpointHeartbeatRepository heartbeatRepository;
    private final EndpointAuditService auditService;
    private final java.time.Clock clock;

    private final boolean featureEnabled;
    private final Set<UUID> allowedTenantIds;
    private final Duration heartbeatFreshnessTtl;
    private final String requiredCapability;
    private final Duration commandTtl;
    private final int maxRoots;

    public EndpointBackupDryrunService(
            EndpointBackupDryrunRequestRepository requestRepository,
            EndpointBackupDryrunManagedRootRepository rootRepository,
            EndpointDeviceRepository deviceRepository,
            EndpointCommandRepository commandRepository,
            EndpointHeartbeatRepository heartbeatRepository,
            EndpointAuditService auditService,
            java.time.Clock clock,
            @Value("${endpoint-admin.backup-dryrun.enabled:false}") boolean featureEnabled,
            @Value("${endpoint-admin.backup-dryrun.allowed-tenant-ids:}") String allowedTenantIdsCsv,
            @Value("${endpoint-admin.backup-dryrun.heartbeat-freshness-ttl:PT5M}") Duration heartbeatFreshnessTtl,
            @Value("${endpoint-admin.backup-dryrun.required-capability:COLLECT_BACKUP_DRYRUN}") String requiredCapability,
            @Value("${endpoint-admin.backup-dryrun.command-ttl:PT30M}") Duration commandTtl,
            @Value("${endpoint-admin.backup-dryrun.max-roots:32}") int maxRoots) {
        this.requestRepository = requestRepository;
        this.rootRepository = rootRepository;
        this.deviceRepository = deviceRepository;
        this.commandRepository = commandRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.featureEnabled = featureEnabled;
        this.allowedTenantIds = parseTenantIds(allowedTenantIdsCsv);
        this.heartbeatFreshnessTtl = heartbeatFreshnessTtl == null ? Duration.ofMinutes(5) : heartbeatFreshnessTtl;
        this.requiredCapability = (requiredCapability == null || requiredCapability.isBlank())
                ? "COLLECT_BACKUP_DRYRUN" : requiredCapability;
        this.commandTtl = commandTtl == null ? Duration.ofMinutes(30) : commandTtl;
        this.maxRoots = maxRoots < 1 ? 32 : maxRoots;
    }

    // ───────────────────────────── PROPOSE ─────────────────────────────

    @Transactional
    public AdminBackupDryrunRequestResponse propose(AdminTenantContext context, UUID deviceId,
                                                    AdminBackupDryrunRequestCreate request) {
        assertEnabledForTenant(context.tenantId());
        if (request == null) {
            throw badRequest("backup dry-run request body is required");
        }
        UUID tenantId = context.tenantId();
        String subject = subject(context);

        List<String> rootRefs = normalizeRootRefs(request.rootRefs());
        String profile = trim(request.allowlistProfileId());
        if (profile == null || !PROFILE_OPAQUE.matcher(profile).matches()) {
            throw badRequest("allowlistProfileId must be opaque ([A-Za-z0-9._:-]+)");
        }
        // Codex 019ec45e P1: the opaque charset still admits "C:foo" / ".." —
        // allowlistProfileId is echoed in the response/audit/dispatch, so it must
        // be path-free too (drive-letter / dotdot / backslash rejected).
        assertPathFree(profile);
        String reason = trim(request.reason());
        if (reason == null || reason.isEmpty()) {
            throw badRequest("reason is required");
        }
        assertPathFree(reason); // reason is operator free-text → must not carry a raw path

        // Codex 019ec45e P1: a caller-supplied idempotency key persists in the
        // request row AND the audit correlation_id (length=128), so it must be
        // opaque + path-free + ≤128 (a raw path would leak via the audit trail).
        String suppliedKey = trim(request.idempotencyKey());
        if (suppliedKey != null && !suppliedKey.isEmpty()) {
            if (!IDEMPOTENCY_KEY_OPAQUE.matcher(suppliedKey).matches()) {
                throw badRequest("idempotencyKey must be opaque ([A-Za-z0-9._:-]+) and ≤128 chars");
            }
            assertPathFree(suppliedKey);
        }

        EndpointDevice device = EndpointDeviceWriteGuard.loadActiveForUpdate(deviceRepository, tenantId, deviceId);

        String idempotencyKey = resolveIdempotencyKey(deviceId, rootRefs, profile, request.idempotencyKey());

        // (1) idempotency replay FIRST
        Optional<EndpointBackupDryrunRequest> existing =
                requestRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            EndpointBackupDryrunRequest e = existing.get();
            // Codex 019ec45e P1: an idempotency-key replay must match the STABLE
            // request shape — device + profile + (canonical) root scope. A reuse
            // with a different scope/profile is a 409, not a silent replay of the
            // old scope. (reason is not part of the stable identity.)
            List<String> existingRefs = e.getRootsSnapshot().stream()
                    .map(BackupDryrunRootSnapshot::rootRef).sorted().toList();
            List<String> incomingRefs = rootRefs.stream().sorted().toList();
            if (!Objects.equals(e.getDeviceId(), deviceId)
                    || !Objects.equals(e.getAllowlistProfileId(), profile)
                    || !existingRefs.equals(incomingRefs)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "idempotency key already used for a different device / profile / root scope");
            }
            return AdminBackupDryrunRequestResponse.from(e);
        }

        // (2) single-flight: ONE ACTIVE dry-run per device (Codex 019ec45e #3).
        // (a) a PENDING_APPROVAL request (DB partial unique is the hard guard);
        // (b) an APPROVED request whose dispatched command is still non-terminal
        //     (don't start a second dry-run while the first is in flight).
        if (requestRepository.findOpenForDevice(tenantId, deviceId, BackupDryrunRequestState.PENDING_APPROVAL).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an open (PENDING_APPROVAL) backup dry-run request already exists for this device");
        }
        boolean approvedInFlight = requestRepository
                .findByTenantIdAndDeviceIdAndState(tenantId, deviceId, BackupDryrunRequestState.APPROVED).stream()
                .map(EndpointBackupDryrunRequest::getCommandId)
                .filter(Objects::nonNull)
                .map(commandRepository::findById)
                .filter(Optional::isPresent).map(Optional::get)
                .anyMatch(c -> !isTerminal(c.getStatus()));
        if (approvedInFlight) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an approved backup dry-run is still in flight for this device; wait for it to finish");
        }

        // (3) resolve roots against the registry (enabled + company-managed only)
        List<EndpointBackupDryrunManagedRoot> resolved = resolveRootsOrThrow(tenantId, rootRefs);
        List<BackupDryrunRootSnapshot> snapshot = resolved.stream()
                .map(r -> new BackupDryrunRootSnapshot(r.getRootRef(), r.getRootVersion()))
                .collect(Collectors.toList());

        Instant now = Instant.now(clock);
        EndpointBackupDryrunRequest entity = new EndpointBackupDryrunRequest();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setDeviceId(deviceId);
        entity.setState(BackupDryrunRequestState.PENDING_APPROVAL);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setAllowlistProfileId(profile);
        entity.setByod(false);
        entity.setReason(reason);
        entity.setRootsSnapshot(snapshot);
        entity.setCreatedBy(subject);
        entity.setCreatedAt(now);
        entity.setStateUpdatedAt(now);

        EndpointBackupDryrunRequest saved;
        try {
            saved = requestRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException dive) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "an open backup dry-run request already exists for this device");
        }

        auditService.record(tenantId, device, null,
                "ENDPOINT_BACKUP_DRYRUN_PROPOSED", "PROPOSE_BACKUP_DRYRUN", subject,
                saved.getIdempotencyKey(), proposeAuditMetadata(saved), null, null);
        return AdminBackupDryrunRequestResponse.from(saved);
    }

    // ───────────────────────────── APPROVE ─────────────────────────────

    @Transactional(noRollbackFor = EndpointBackupDryrunMakerCheckerViolationException.class)
    public AdminBackupDryrunRequestResponse approve(AdminTenantContext context, UUID deviceId,
                                                    UUID requestId, AdminBackupDryrunRequestApproval body) {
        assertEnabledForTenant(context.tenantId());
        UUID tenantId = context.tenantId();
        String subject = subject(context);

        // lock order: device first (mirrors decommission), then request row
        EndpointDevice device = EndpointDeviceWriteGuard.loadActiveForUpdate(deviceRepository, tenantId, deviceId);
        EndpointBackupDryrunRequest req = requestRepository.findByTenantIdAndIdForUpdate(tenantId, requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "backup dry-run request not found in tenant scope"));
        if (!Objects.equals(req.getDeviceId(), deviceId)) {
            throw badRequest("deviceId in path does not match the request's device");
        }
        if (req.getState() != BackupDryrunRequestState.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "backup dry-run request is not PENDING_APPROVAL; current state=" + req.getState());
        }
        if (Objects.equals(req.getCreatedBy(), subject)) {
            auditService.record(tenantId, null, null,
                    "ENDPOINT_BACKUP_DRYRUN_APPROVAL_REJECTED_MAKER_CHECKER", "APPROVE_BACKUP_DRYRUN", subject,
                    req.getIdempotencyKey(),
                    Map.of("requestId", req.getId().toString(), "createdBy", req.getCreatedBy(),
                            "approverSubject", subject),
                    null, null);
            throw new EndpointBackupDryrunMakerCheckerViolationException(req.getId(), req.getCreatedBy(), subject);
        }

        String approveReason = trim(body == null ? null : body.reason());
        if (approveReason != null) {
            assertPathFree(approveReason);
        }

        // (5) drift revalidation: each snapshot root must STILL resolve
        // (enabled + company-managed) AND its CURRENT registry root_version must
        // equal the propose-time snapshot. Drift → 409 re-propose (NEVER silent
        // re-resolve — the approved scope must equal the dispatched scope).
        List<String> rootRefs = req.getRootsSnapshot().stream().map(BackupDryrunRootSnapshot::rootRef).toList();
        List<EndpointBackupDryrunManagedRoot> resolved = resolveRootsOrThrow(tenantId, rootRefs);
        Map<String, Integer> currentVersion = resolved.stream()
                .collect(Collectors.toMap(EndpointBackupDryrunManagedRoot::getRootRef,
                        EndpointBackupDryrunManagedRoot::getRootVersion));
        for (BackupDryrunRootSnapshot snap : req.getRootsSnapshot()) {
            Integer cur = currentVersion.get(snap.rootRef());
            if (cur == null || cur.intValue() != snap.rootVersion()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "registry drift: a root changed (or was disabled) since propose; re-propose to refresh the scope");
            }
        }

        // (6) heartbeat freshness + capability (same most-recent heartbeat row)
        Instant now = Instant.now(clock);
        assertHeartbeatFreshAndCapable(device, now);

        // (7) transition before building payload
        req.setState(BackupDryrunRequestState.APPROVED);
        req.setApprovedBy(subject);
        req.setStateUpdatedAt(now);

        // (8) dispatch payload (dataprotection.Request shape). local_path is
        // resolved HERE only; the generic command DTO redacts it.
        Map<String, Object> payload = buildDispatchPayload(tenantId, deviceId, req, resolved);

        // (9) create the COLLECT_BACKUP_DRYRUN command (TTL-bounded — never null)
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(CommandType.COLLECT_BACKUP_DRYRUN);
        command.setIdempotencyKey("admin-backup-dryrun-cmd:" + req.getId());
        command.setStatus(CommandStatus.QUEUED);
        command.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
        command.setPayload(payload);
        command.setPriority(100);
        command.setAttemptCount(0);
        command.setMaxAttempts(3);
        command.setVisibleAfterAt(now);
        command.setExpiresAt(now.plus(commandTtl)); // Codex: bounded TTL, no null expiry
        command.setIssuedBySubject(subject);
        command.setIssuedAt(now);
        EndpointCommand savedCommand = commandRepository.saveAndFlush(command);

        req.setCommandId(savedCommand.getId());
        EndpointBackupDryrunRequest savedReq = requestRepository.save(req);

        auditService.record(tenantId, device, savedCommand,
                "ENDPOINT_BACKUP_DRYRUN_APPROVED_DISPATCHED", "APPROVE_BACKUP_DRYRUN", subject,
                savedReq.getIdempotencyKey(), approveAuditMetadata(savedReq, savedCommand), null, null);
        return AdminBackupDryrunRequestResponse.from(savedReq);
    }

    // ───────────────────────────── READS ─────────────────────────────

    @Transactional(readOnly = true)
    public AdminBackupDryrunRequestResponse get(AdminTenantContext context, UUID deviceId, UUID requestId) {
        assertEnabledForTenant(context.tenantId());
        EndpointBackupDryrunRequest req = requestRepository.findByTenantIdAndId(context.tenantId(), requestId)
                .filter(r -> Objects.equals(r.getDeviceId(), deviceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "backup dry-run request not found"));
        return AdminBackupDryrunRequestResponse.from(req);
    }

    @Transactional(readOnly = true)
    public List<AdminBackupDryrunRequestResponse> listForDevice(AdminTenantContext context, UUID deviceId,
                                                                int page, int size) {
        assertEnabledForTenant(context.tenantId());
        int boundedSize = Math.min(Math.max(size, 1), 200);
        return requestRepository
                .findByTenantIdAndDeviceIdOrderByCreatedAtDesc(context.tenantId(), deviceId,
                        PageRequest.of(Math.max(page, 0), boundedSize))
                .stream().map(AdminBackupDryrunRequestResponse::from).collect(Collectors.toList());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private List<EndpointBackupDryrunManagedRoot> resolveRootsOrThrow(UUID tenantId, List<String> rootRefs) {
        List<EndpointBackupDryrunManagedRoot> resolved = rootRepository
                .findByTenantIdAndEnabledTrueAndCompanyManagedTrueAndRootRefIn(tenantId, rootRefs);
        if (resolved.size() != rootRefs.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "one or more rootRefs are unknown, disabled, or not company-managed in the registry");
        }
        // canonical order (Codex 019ec45e #8) so the propose snapshot + the
        // dispatch payload are deterministic regardless of registry row order.
        resolved.sort(java.util.Comparator.comparing(EndpointBackupDryrunManagedRoot::getRootRef));
        return resolved;
    }

    private static boolean isTerminal(CommandStatus s) {
        return s == CommandStatus.SUCCEEDED || s == CommandStatus.FAILED
                || s == CommandStatus.CANCELLED || s == CommandStatus.EXPIRED;
    }

    private Map<String, Object> buildDispatchPayload(UUID tenantId, UUID deviceId,
                                                     EndpointBackupDryrunRequest req,
                                                     List<EndpointBackupDryrunManagedRoot> resolved) {
        List<Map<String, Object>> roots = new ArrayList<>();
        for (EndpointBackupDryrunManagedRoot r : resolved) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("root_ref", r.getRootRef());
            root.put("local_path", r.getLocalPath()); // resolved only at dispatch
            root.put("path_class", r.getPathClass());
            root.put("company_managed", true);
            roots.add(root);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_id", deviceId.toString());
        payload.put("tenant_id", tenantId.toString());
        payload.put("allowlist_profile_id", req.getAllowlistProfileId());
        payload.put("byod", false);
        payload.put("roots", roots);
        return payload;
    }

    private void assertHeartbeatFreshAndCapable(EndpointDevice device, Instant now) {
        Optional<EndpointHeartbeat> latest = heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(device.getId());
        Instant receivedAt = latest.map(EndpointHeartbeat::getReceivedAt).orElse(null);
        boolean fresh = receivedAt != null
                && Duration.between(receivedAt, now).compareTo(heartbeatFreshnessTtl) <= 0;
        if (!fresh) {
            throw new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
                    "agent heartbeat is stale; retry after the agent reconnects");
        }
        boolean advertised = latest.map(EndpointHeartbeat::getPayload)
                .map(p -> p.get(HEARTBEAT_CAPABILITIES_KEY))
                .map(this::containsRequiredCapability).orElse(false);
        if (!advertised) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "agent does not advertise the '" + requiredCapability + "' capability on the latest heartbeat");
        }
    }

    private boolean containsRequiredCapability(Object node) {
        if (node instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && requiredCapability.equalsIgnoreCase(String.valueOf(item).trim())) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof Map<?, ?> map) {
            Object v = map.get(requiredCapability);
            return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
        }
        return false;
    }

    private void assertEnabledForTenant(UUID tenantId) {
        if (!featureEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Backup dry-run surface is disabled (endpoint-admin.backup-dryrun.enabled=false).");
        }
        if (!allowedTenantIds.contains(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Backup dry-run surface is not enabled for this tenant.");
        }
    }

    private List<String> normalizeRootRefs(List<String> rootRefs) {
        if (rootRefs == null || rootRefs.isEmpty()) {
            throw badRequest("rootRefs is required and must be non-empty");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String r : rootRefs) {
            String t = trim(r);
            if (t == null || t.isEmpty()) {
                throw badRequest("rootRefs must not contain blank entries");
            }
            unique.add(t);
        }
        if (unique.size() != rootRefs.size()) {
            throw badRequest("rootRefs must be unique");
        }
        if (unique.size() > maxRoots) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "too many rootRefs (max " + maxRoots + ")");
        }
        return new ArrayList<>(unique);
    }

    private static String resolveIdempotencyKey(UUID deviceId, List<String> rootRefs, String profile, String supplied) {
        String s = supplied == null ? null : supplied.trim();
        if (s != null && !s.isEmpty()) {
            return s;
        }
        List<String> sorted = new ArrayList<>(rootRefs);
        sorted.sort(String::compareTo);
        String material = deviceId + "|" + profile + "|" + String.join(",", sorted);
        return "bdr-" + deviceId + "-" + sha256Hex(material).substring(0, 24);
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void assertPathFree(String s) {
        if (s == null) {
            return;
        }
        if (s.indexOf('\\') >= 0 || s.contains("..") || DRIVE_PATH.matcher(s).find()) {
            throw badRequest("field must not contain a filesystem path (backslash / drive-letter / ..)");
        }
    }

    private Map<String, Object> proposeAuditMetadata(EndpointBackupDryrunRequest r) {
        // PATH-FREE: opaque root_refs + profile + counts only.
        return Map.of(
                "requestId", r.getId().toString(),
                "rootRefs", r.getRootsSnapshot().stream().map(BackupDryrunRootSnapshot::rootRef).toList(),
                "rootCount", r.getRootsSnapshot().size(),
                "allowlistProfileId", r.getAllowlistProfileId(),
                "byod", r.isByod());
    }

    private Map<String, Object> approveAuditMetadata(EndpointBackupDryrunRequest r, EndpointCommand command) {
        return Map.of(
                "requestId", r.getId().toString(),
                "commandId", command.getId().toString(),
                "rootCount", r.getRootsSnapshot().size(),
                "allowlistProfileId", r.getAllowlistProfileId());
    }

    private static Set<UUID> parseTenantIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(UUID::fromString).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String subject(AdminTenantContext context) {
        String s = context == null ? null : context.subject();
        return (s == null || s.isBlank()) ? "system" : s;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
