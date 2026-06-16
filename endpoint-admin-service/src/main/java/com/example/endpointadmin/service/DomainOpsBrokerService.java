package com.example.endpointadmin.service;

import com.example.endpointadmin.domainops.DomainOpsOperation;
import com.example.endpointadmin.domainops.DomainOpsResult;
import com.example.endpointadmin.domainops.DomainOpsStatus;
import com.example.endpointadmin.dto.v1.admin.CreateDomainOpsRequest;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class DomainOpsBrokerService {

    public static final String EVENT_TYPE_REQUESTED = "DOMAIN_OPS_REQUESTED";
    public static final String EVENT_TYPE_DENIED = "DOMAIN_OPS_DENIED";
    private static final Duration HARD_MAX_PERMIT_TTL = Duration.ofMinutes(15);
    private static final Duration DEFAULT_REQUEST_TTL = Duration.ofMinutes(5);

    private final EndpointDeviceRepository deviceRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;
    private final boolean enabled;
    private final Duration configuredMaxPermitTtl;

    public DomainOpsBrokerService(EndpointDeviceRepository deviceRepository,
                                  EndpointAuditService auditService,
                                  Clock clock,
                                  @Value("${endpoint-admin.domain-ops.enabled:false}") boolean enabled,
                                  @Value("${endpoint-admin.domain-ops.max-permit-ttl:PT15M}") Duration configuredMaxPermitTtl) {
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.enabled = enabled;
        this.configuredMaxPermitTtl = configuredMaxPermitTtl == null
                ? HARD_MAX_PERMIT_TTL
                : clampPermitTtl(configuredMaxPermitTtl);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public DomainOpsResult create(AdminTenantContext context,
                                  UUID deviceId,
                                  CreateDomainOpsRequest request) {
        AdminTenantContext resolved = requireContext(context);
        if (deviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Endpoint device id is required.");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain ops request body is required.");
        }

        if (!enabled) {
            auditDenied(resolved, null, deviceId, request, "domain-ops-disabled");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Domain ops broker is disabled.");
        }

        EndpointDevice device = deviceRepository.findVisibleToOrgAndId(resolved.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Endpoint device was not found."));

        DomainOpsOperation operation = DomainOpsOperation.parse(request.operation())
                .orElse(null);
        if (operation == null) {
            DomainOpsResult denied = auditDenied(resolved, device, request,
                    "unsupported-operation");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain operation is not supported by the pilot broker: "
                            + safeOperationName(request.operation()));
        }

        Duration ttl = resolveTtl(request.ttlSeconds());
        Duration maxTtl = maxPermitTtl();
        if (ttl.compareTo(maxTtl) > 0) {
            auditDenied(resolved, device, request, "ttl-exceeds-max");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain operation TTL exceeds the broker maximum.");
        }

        String reason = normalizeReason(request.reason());
        if (reason == null) {
            auditDenied(resolved, device, request, "reason-required");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain operation reason is required.");
        }
        if (reason.length() > 512) {
            auditDenied(resolved, device, request, "reason-too-long");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain operation reason must be at most 512 characters.");
        }

        DomainOpsResult result = new DomainOpsResult(
                UUID.randomUUID(),
                resolved.tenantId(),
                device.getId(),
                operation.name(),
                DomainOpsStatus.PENDING_DISPATCH,
                "awaiting-domain-connector",
                ttl.toSeconds(),
                resolved.subject(),
                Instant.now(clock));

        auditService.record(
                resolved.tenantId(),
                device,
                null,
                EVENT_TYPE_REQUESTED,
                "domain-ops.requested",
                resolved.subject(),
                result.operationId().toString(),
                metadata(result, reason, request.idempotencyKey()),
                null,
                null);
        return result;
    }

    private DomainOpsResult auditDenied(AdminTenantContext context,
                                        EndpointDevice device,
                                        CreateDomainOpsRequest request,
                                        String reasonCode) {
        return auditDenied(context, device, device.getId(), request, reasonCode);
    }

    private DomainOpsResult auditDenied(AdminTenantContext context,
                                        EndpointDevice device,
                                        UUID requestedDeviceId,
                                        CreateDomainOpsRequest request,
                                        String reasonCode) {
        DomainOpsResult result = new DomainOpsResult(
                UUID.randomUUID(),
                context.tenantId(),
                requestedDeviceId,
                safeOperationName(request == null ? null : request.operation()),
                DomainOpsStatus.DENIED,
                reasonCode,
                resolveTtl(request == null ? null : request.ttlSeconds()).toSeconds(),
                context.subject(),
                Instant.now(clock));
        auditService.record(
                context.tenantId(),
                device,
                null,
                EVENT_TYPE_DENIED,
                "domain-ops.denied",
                context.subject(),
                result.operationId().toString(),
                metadata(result,
                        normalizeReason(request == null ? null : request.reason()),
                        request == null ? null : request.idempotencyKey()),
                null,
                null);
        return result;
    }

    private AdminTenantContext requireContext(AdminTenantContext context) {
        if (context == null || context.tenantId() == null
                || context.subject() == null || context.subject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Admin tenant context is required.");
        }
        return context;
    }

    private Duration maxPermitTtl() {
        return clampPermitTtl(configuredMaxPermitTtl);
    }

    private Duration clampPermitTtl(Duration value) {
        Duration normalized = value;
        if (normalized.isNegative() || normalized.isZero()) {
            normalized = HARD_MAX_PERMIT_TTL;
        }
        return normalized.compareTo(HARD_MAX_PERMIT_TTL) > 0
                ? HARD_MAX_PERMIT_TTL
                : normalized;
    }

    private Duration resolveTtl(Long ttlSeconds) {
        if (ttlSeconds == null) {
            return DEFAULT_REQUEST_TTL;
        }
        if (ttlSeconds <= 0) {
            return DEFAULT_REQUEST_TTL;
        }
        return Duration.ofSeconds(ttlSeconds);
    }

    private String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeOperationName(String operation) {
        if (operation == null || operation.isBlank()) {
            return "UNKNOWN";
        }
        return operation.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
    }

    private Map<String, Object> metadata(DomainOpsResult result,
                                         String reason,
                                         String idempotencyKey) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("operationId", result.operationId().toString());
        metadata.put("deviceId", result.deviceId() == null ? null : result.deviceId().toString());
        metadata.put("operation", result.operation());
        metadata.put("status", result.status().name());
        metadata.put("reasonCode", result.reasonCode());
        metadata.put("reason", reason);
        metadata.put("ttlSeconds", result.ttlSeconds());
        metadata.put("maxPermitTtlSeconds", maxPermitTtl().toSeconds());
        metadata.put("requestedBy", result.requestedBy());
        metadata.put("idempotencyKeyHash", sha256OrNull(idempotencyKey));
        metadata.put("contract", "agent-198:max-permit-ttl-15m,mtls-only,no-raw-shell");
        return metadata;
    }

    private String sha256OrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
