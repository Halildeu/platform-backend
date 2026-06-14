package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminManagedRootCreateRequest;
import com.example.endpointadmin.dto.v1.admin.AdminManagedRootResponse;
import com.example.endpointadmin.dto.v1.admin.AdminManagedRootSetEnabledRequest;
import com.example.endpointadmin.model.EndpointBackupDryrunManagedRoot;
import com.example.endpointadmin.repository.EndpointBackupDryrunManagedRootRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Faz 22.8A.3a (#648) — managed-data-root registry service (contract §4; Codex
 * 019ec45e "registry-first"). The backup dry-run issuing surface (22.8A.3b)
 * references roots by OPAQUE {@code rootRef}; this service owns the registry
 * (the only place the raw {@code localPath} is accepted/stored) and keeps the
 * raw path internal — responses are path-free.
 *
 * <p>Double-gated + fail-closed:
 * <ul>
 *   <li>{@code endpoint-admin.backup-dryrun.enabled=false} (default) → 503.</li>
 *   <li>per-tenant opt-in: {@code allowed-tenant-ids} default EMPTY → every
 *       tenant 403 until explicitly listed.</li>
 *   <li>{@code rootRef} opaque {@code managed_root:[A-Za-z0-9._-]+} (mirrors the
 *       agent + the BackupDryRunManifestPayloadPolicy validator).</li>
 *   <li>{@code pathClass} bounded enum; BYOD (non-company-managed) roots are
 *       rejected in this slice (Codex BYOD-fail-closed).</li>
 * </ul>
 */
@Service
public class EndpointBackupDryrunRegistryService {

    private static final String ROOT_REF_PREFIX = "managed_root:";

    /** Bounded path_class enum — identical to BackupDryRunManifestPayloadPolicy. */
    private static final Set<String> PATH_CLASSES = Set.of(
            "managed/onedrive-business", "managed/sharepoint", "managed/unc-corp",
            "managed/it-folder", "mdm-gpo-root");

    private final EndpointBackupDryrunManagedRootRepository rootRepository;
    private final boolean featureEnabled;
    private final Set<UUID> allowedTenantIds;

    public EndpointBackupDryrunRegistryService(
            EndpointBackupDryrunManagedRootRepository rootRepository,
            @Value("${endpoint-admin.backup-dryrun.enabled:false}") boolean featureEnabled,
            @Value("${endpoint-admin.backup-dryrun.allowed-tenant-ids:}") String allowedTenantIdsCsv) {
        this.rootRepository = rootRepository;
        this.featureEnabled = featureEnabled;
        this.allowedTenantIds = parseTenantIds(allowedTenantIdsCsv);
    }

    @Transactional
    public AdminManagedRootResponse register(AdminTenantContext context, AdminManagedRootCreateRequest request) {
        assertEnabledForTenant(context.tenantId());
        if (request == null) {
            throw badRequest("managed-root request body is required");
        }
        String rootRef = trim(request.rootRef());
        if (!validRootRef(rootRef)) {
            throw badRequest("rootRef must be an opaque managed_root:<token> (token [A-Za-z0-9._-]+)");
        }
        String pathClass = trim(request.pathClass());
        if (!PATH_CLASSES.contains(pathClass)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "pathClass out of bounded enum");
        }
        if (request.companyManaged() == null || !request.companyManaged()) {
            // Codex BYOD-fail-closed: only company-managed roots in this slice.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "only company-managed roots are supported in this slice (BYOD fail-closed)");
        }
        String localPath = trim(request.localPath());
        if (localPath == null || localPath.isEmpty() || localPath.indexOf((char) 0) >= 0) {
            throw badRequest("localPath is required and must not contain a NUL byte");
        }
        rootRepository.findByTenantIdAndRootRef(context.tenantId(), rootRef).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a managed root with this rootRef already exists for the tenant");
        });

        EndpointBackupDryrunManagedRoot root = new EndpointBackupDryrunManagedRoot();
        root.setId(UUID.randomUUID());
        root.setTenantId(context.tenantId());
        root.setRootRef(rootRef);
        root.setPathClass(pathClass);
        root.setLocalPath(localPath);
        root.setCompanyManaged(true);
        root.setEnabled(true);
        root.setRootVersion(1);
        root.setCreatedBy(subject(context));
        root.setUpdatedBy(subject(context));
        return AdminManagedRootResponse.from(rootRepository.saveAndFlush(root));
    }

    @Transactional(readOnly = true)
    public List<AdminManagedRootResponse> list(AdminTenantContext context, int page, int size) {
        assertEnabledForTenant(context.tenantId());
        int boundedSize = Math.min(Math.max(size, 1), 200);
        return rootRepository
                .findByTenantIdOrderByCreatedAtDesc(context.tenantId(),
                        PageRequest.of(Math.max(page, 0), boundedSize))
                .stream()
                .map(AdminManagedRootResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public AdminManagedRootResponse setEnabled(AdminTenantContext context, UUID id,
                                               AdminManagedRootSetEnabledRequest request) {
        assertEnabledForTenant(context.tenantId());
        if (request == null || request.enabled() == null) {
            throw badRequest("enabled flag is required");
        }
        EndpointBackupDryrunManagedRoot root = rootRepository
                .findByIdAndTenantId(id, context.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "managed root not found"));
        root.setEnabled(request.enabled());
        root.setUpdatedBy(subject(context));
        return AdminManagedRootResponse.from(rootRepository.saveAndFlush(root));
    }

    private void assertEnabledForTenant(UUID tenantId) {
        if (!featureEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Backup dry-run surface is disabled (endpoint-admin.backup-dryrun.enabled=false).");
        }
        // Per-tenant opt-in, fail-closed: an empty allow-list means NO tenant is
        // enabled (the surface stays dark until a tenant is explicitly listed).
        if (!allowedTenantIds.contains(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Backup dry-run surface is not enabled for this tenant.");
        }
    }

    /** Positive allowlist — mirrors the agent + BackupDryRunManifestPayloadPolicy. */
    private static boolean validRootRef(String s) {
        if (s == null || !s.startsWith(ROOT_REF_PREFIX)) {
            return false;
        }
        String token = s.substring(ROOT_REF_PREFIX.length());
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static Set<UUID> parseTenantIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
