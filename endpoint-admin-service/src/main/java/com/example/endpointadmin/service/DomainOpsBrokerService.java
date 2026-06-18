package com.example.endpointadmin.service;

import com.example.endpointadmin.domainops.DomainOpsConnector;
import com.example.endpointadmin.domainops.DomainOpsConnectorDispatchRequest;
import com.example.endpointadmin.domainops.DomainOpsConnectorDispatchResult;
import com.example.endpointadmin.domainops.DomainOpsCredentialRefPolicy;
import com.example.endpointadmin.domainops.DomainOpsOperation;
import com.example.endpointadmin.domainops.DomainOpsOperationPayloadPolicy;
import com.example.endpointadmin.domainops.DomainOpsResult;
import com.example.endpointadmin.domainops.DomainOpsResultSubmitPolicy;
import com.example.endpointadmin.domainops.DomainOpsStatus;
import com.example.endpointadmin.domainops.UnavailableDomainOpsConnector;
import com.example.endpointadmin.dto.v1.admin.CreateDomainOpsRequest;
import com.example.endpointadmin.dto.v1.admin.SubmitDomainOpsResultRequest;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDomainOpsRequest;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointDomainOpsRequestRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class DomainOpsBrokerService {

    public static final String EVENT_TYPE_REQUESTED = "DOMAIN_OPS_REQUESTED";
    public static final String EVENT_TYPE_DENIED = "DOMAIN_OPS_DENIED";
    public static final String EVENT_TYPE_DISPATCHED = "DOMAIN_OPS_DISPATCHED";
    public static final String EVENT_TYPE_SUCCEEDED = "DOMAIN_OPS_SUCCEEDED";
    public static final String EVENT_TYPE_FAILED = "DOMAIN_OPS_FAILED";
    public static final String EVENT_TYPE_EXPIRED = "DOMAIN_OPS_EXPIRED";

    private static final Duration HARD_MAX_PERMIT_TTL = Duration.ofMinutes(15);
    private static final Duration DEFAULT_REQUEST_TTL = Duration.ofMinutes(5);
    private static final String CONTRACT = "agent-198:max-permit-ttl-15m,mtls-only,no-raw-shell,credential-ref-only";

    private final EndpointDeviceRepository deviceRepository;
    private final EndpointDomainOpsRequestRepository requestRepository;
    private final EndpointAuditService auditService;
    private final DomainOpsConnector connector;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final Duration configuredMaxPermitTtl;

    @Autowired
    public DomainOpsBrokerService(EndpointDeviceRepository deviceRepository,
                                  EndpointDomainOpsRequestRepository requestRepository,
                                  EndpointAuditService auditService,
                                  Clock clock,
                                  PlatformTransactionManager transactionManager,
                                  ObjectProvider<DomainOpsConnector> connectorProvider,
                                  @Value("${endpoint-admin.domain-ops.enabled:false}") boolean enabled,
                                  @Value("${endpoint-admin.domain-ops.max-permit-ttl:PT15M}") Duration configuredMaxPermitTtl) {
        this(deviceRepository,
                requestRepository,
                auditService,
                clock,
                transactionManager,
                connectorProvider == null
                        ? new UnavailableDomainOpsConnector()
                        : connectorProvider.getIfAvailable(UnavailableDomainOpsConnector::new),
                enabled,
                configuredMaxPermitTtl);
    }

    DomainOpsBrokerService(EndpointDeviceRepository deviceRepository,
                           EndpointDomainOpsRequestRepository requestRepository,
                           EndpointAuditService auditService,
                           Clock clock,
                           PlatformTransactionManager transactionManager,
                           boolean enabled,
                           Duration configuredMaxPermitTtl) {
        this(deviceRepository,
                requestRepository,
                auditService,
                clock,
                transactionManager,
                new UnavailableDomainOpsConnector(),
                enabled,
                configuredMaxPermitTtl);
    }

    DomainOpsBrokerService(EndpointDeviceRepository deviceRepository,
                           EndpointDomainOpsRequestRepository requestRepository,
                           EndpointAuditService auditService,
                           Clock clock,
                           PlatformTransactionManager transactionManager,
                           DomainOpsConnector connector,
                           boolean enabled,
                           Duration configuredMaxPermitTtl) {
        this.deviceRepository = deviceRepository;
        this.requestRepository = requestRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.connector = connector == null ? new UnavailableDomainOpsConnector() : connector;
        this.enabled = enabled;
        this.configuredMaxPermitTtl = configuredMaxPermitTtl == null
                ? HARD_MAX_PERMIT_TTL
                : clampPermitTtl(configuredMaxPermitTtl);
    }

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

        TxOutcome outcome;
        try {
            outcome = transactionTemplate.execute(status ->
                    createDurableRequest(resolved, deviceId, request));
        } catch (DataIntegrityViolationException ex) {
            outcome = transactionTemplate.execute(status ->
                    replayAfterIdempotencyConflict(resolved, request, ex));
        }
        if (outcome instanceof TxRejected rejected) {
            throw rejected.exception();
        }
        if (outcome instanceof TxReplay replay) {
            return replay.result();
        }

        TxAccepted accepted = (TxAccepted) outcome;
        DomainOpsConnectorDispatchResult dispatchResult = dispatch(accepted.plan());
        return transactionTemplate.execute(status ->
                persistDispatchResult(accepted.plan().requestId(), dispatchResult));
    }

    public DomainOpsResult submitResult(AdminTenantContext context,
                                        UUID deviceId,
                                        UUID operationId,
                                        SubmitDomainOpsResultRequest request) {
        AdminTenantContext resolved = requireContext(context);
        if (deviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Endpoint device id is required.");
        }
        if (operationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain ops operation id is required.");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain ops result body is required.");
        }

        TxOutcome outcome = transactionTemplate.execute(status ->
                persistSubmittedResult(resolved, deviceId, operationId, request));
        if (outcome instanceof TxRejected rejected) {
            throw rejected.exception();
        }
        return ((TxReplay) outcome).result();
    }

    public int expireStaleDispatchWindows(int maxRequests) {
        if (maxRequests <= 0) {
            return 0;
        }
        int bounded = Math.min(maxRequests, 500);
        return transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<EndpointDomainOpsRequest> candidates = requestRepository.findExpiredDispatchCandidates(
                    List.of(DomainOpsStatus.ACCEPTED, DomainOpsStatus.PENDING_DISPATCH, DomainOpsStatus.DISPATCHED),
                    now,
                    PageRequest.of(0, bounded));
            int expired = 0;
            for (EndpointDomainOpsRequest stored : candidates) {
                stored.markConnectorResult(
                        DomainOpsStatus.EXPIRED,
                        "dispatch-window-expired",
                        "domain-ops-reconciler",
                        null,
                        Map.of("reconciledBy", "domain-ops-reconciler"),
                        now);
                stored = requestRepository.saveAndFlush(stored);
                EndpointDevice device = deviceRepository.findById(stored.getDeviceId()).orElse(null);
                auditStoredRequest(stored,
                        device,
                        EVENT_TYPE_EXPIRED,
                        "domain-ops.expired",
                        stored.getCredentialRef() != null,
                        stored.getCredentialRefHash() != null);
                expired++;
            }
            return expired;
        });
    }

    private TxOutcome replayAfterIdempotencyConflict(AdminTenantContext resolved,
                                                     CreateDomainOpsRequest request,
                                                     DataIntegrityViolationException cause) {
        String idempotencyKeyHash = sha256OrNull(request == null ? null : request.idempotencyKey());
        if (idempotencyKeyHash == null) {
            throw cause;
        }
        return requestRepository.findByTenantIdAndIdempotencyKeyHash(
                        resolved.tenantId(), idempotencyKeyHash)
                .<TxOutcome>map(existing -> new TxReplay(toResult(existing)))
                .orElseThrow(() -> cause);
    }

    private TxOutcome createDurableRequest(AdminTenantContext resolved,
                                           UUID deviceId,
                                           CreateDomainOpsRequest request) {
        if (!enabled) {
            auditDenied(resolved, null, deviceId, request, "domain-ops-disabled");
            return new TxRejected(new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Domain ops broker is disabled."));
        }

        EndpointDevice device = deviceRepository.findVisibleToOrgAndId(resolved.tenantId(), deviceId)
                .orElse(null);
        if (device == null) {
            return new TxRejected(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Endpoint device was not found."));
        }

        DomainOpsOperation operation = DomainOpsOperation.parse(request.operation())
                .orElse(null);
        if (operation == null) {
            auditDenied(resolved, device, request, "unsupported-operation");
            return new TxRejected(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain operation is not supported by the pilot broker: "
                            + safeOperationName(request.operation())));
        }

        Duration ttl = resolveTtl(request.ttlSeconds());
        Duration maxTtl = maxPermitTtl();
        if (ttl.compareTo(maxTtl) > 0) {
            auditDenied(resolved, device, request, "ttl-exceeds-max");
            return new TxRejected(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain operation TTL exceeds the broker maximum."));
        }

        ReasonResolution reason = normalizeRequiredReason(resolved, device, request);
        if (reason.rejection() != null) {
            return new TxRejected(reason.rejection());
        }
        PayloadResolution payload = normalizePayload(resolved, device, request, operation);
        if (payload.rejection() != null) {
            return new TxRejected(payload.rejection());
        }
        String idempotencyKeyHash = sha256OrNull(request.idempotencyKey());
        if (idempotencyKeyHash != null) {
            var existing = requestRepository.findByTenantIdAndIdempotencyKeyHash(
                    resolved.tenantId(), idempotencyKeyHash);
            if (existing.isPresent()) {
                return new TxReplay(toResult(existing.get()));
            }
        }

        Instant requestedAt = clock.instant();
        Instant expiresAt = requestedAt.plus(ttl);
        CredentialRefResolution credentialRef = normalizeCredentialRefOrPersistDenied(
                resolved,
                device,
                request,
                operation,
                reason.value(),
                idempotencyKeyHash,
                ttl,
                requestedAt,
                expiresAt);
        if (credentialRef.rejection() != null) {
            return new TxRejected(credentialRef.rejection());
        }
        String credentialRefHash = sha256OrNull(credentialRef.value());

        EndpointDomainOpsRequest stored = newRequest(
                resolved,
                device,
                operation,
                reason.value(),
                idempotencyKeyHash,
                credentialRef.value(),
                credentialRefHash,
                payload.value(),
                ttl,
                requestedAt,
                expiresAt);
        stored.setState(DomainOpsStatus.ACCEPTED);
        stored.setReasonCode("accepted");
        stored = requestRepository.saveAndFlush(stored);
        auditStoredRequest(stored, device, EVENT_TYPE_REQUESTED, "domain-ops.requested",
                true, true);
        return new TxAccepted(new DispatchPlan(
                stored.getId(),
                stored.getTenantId(),
                stored.getDeviceId(),
                device.getHostname(),
                device.getDomainName(),
                stored.getOperation(),
                stored.getOperationPayload(),
                credentialRef.value(),
                stored.getExpiresAt(),
                stored.getReason()));
    }

    private DomainOpsResult persistDispatchResult(UUID requestId,
                                                  DomainOpsConnectorDispatchResult dispatchResult) {
        EndpointDomainOpsRequest stored = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Domain ops request disappeared before dispatch result persistence."));
        DomainOpsStatus dispatchStatus = safeDispatchStatus(dispatchResult);
        String dispatchReason = safeReasonCode(dispatchResult == null ? null : dispatchResult.reasonCode(),
                dispatchStatus == DomainOpsStatus.FAILED ? "connector-failed" : "connector-dispatched");
        if (dispatchResult == null) {
            dispatchReason = "connector-null-result";
        } else if (dispatchStatus == DomainOpsStatus.FAILED
                && dispatchResult.status() != DomainOpsStatus.FAILED) {
            dispatchReason = "connector-invalid-status";
        }

        stored.markConnectorResult(
                dispatchStatus,
                dispatchReason,
                safeConnectorName(connector.name()),
                trimToMax(dispatchResult == null ? null : dispatchResult.connectorAttemptId(), 128),
                dispatchResult == null ? Map.of() : dispatchResult.redactedResult(),
                clock.instant());
        stored = requestRepository.saveAndFlush(stored);
        EndpointDevice device = deviceRepository.findById(stored.getDeviceId()).orElse(null);
        auditStoredRequest(stored, device, eventTypeFor(stored.getState()), actionFor(stored.getState()),
                true, true);
        return toResult(stored);
    }

    private TxOutcome persistSubmittedResult(AdminTenantContext context,
                                             UUID deviceId,
                                             UUID operationId,
                                             SubmitDomainOpsResultRequest request) {
        EndpointDevice device = deviceRepository.findVisibleToOrgAndId(context.tenantId(), deviceId)
                .orElse(null);
        if (device == null) {
            return new TxRejected(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Endpoint device was not found."));
        }
        EndpointDomainOpsRequest stored = requestRepository.findByIdForUpdate(operationId)
                .orElse(null);
        if (stored == null
                || !Objects.equals(stored.getTenantId(), context.tenantId())
                || !Objects.equals(stored.getDeviceId(), deviceId)) {
            return new TxRejected(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Domain ops request was not found."));
        }
        Instant now = clock.instant();
        if (stored.getState() != DomainOpsStatus.DISPATCHED) {
            return new TxRejected(new ResponseStatusException(HttpStatus.CONFLICT,
                    "Domain ops request is not awaiting an executor result."));
        }
        if (!now.isBefore(stored.getExpiresAt())) {
            stored.markConnectorResult(
                    DomainOpsStatus.EXPIRED,
                    "dispatch-window-expired",
                    stored.getConnectorName(),
                    stored.getConnectorAttemptId(),
                    Map.of("reconciledBy", "domain-ops-result-submit"),
                    now);
            stored = requestRepository.saveAndFlush(stored);
            auditStoredRequest(stored, device, EVENT_TYPE_EXPIRED, "domain-ops.expired",
                    stored.getCredentialRef() != null,
                    stored.getCredentialRefHash() != null);
            return new TxRejected(new ResponseStatusException(HttpStatus.CONFLICT,
                    "Domain ops dispatch window expired."));
        }

        DomainOpsStatus terminalStatus = parseSubmittedStatus(request.status());
        String expectedPackageSha256 = expectedPackageSha256(stored);
        if (expectedPackageSha256 != null) {
            String submittedPackageSha256 = DomainOpsResultSubmitPolicy.normalizeSha256(
                    request.packageSha256(), "packageSha256");
            if (!expectedPackageSha256.equals(submittedPackageSha256)) {
                return new TxRejected(new ResponseStatusException(HttpStatus.CONFLICT,
                        "Domain ops result package digest does not match the dispatched package."));
            }
        } else if (request.packageSha256() != null && !request.packageSha256().isBlank()) {
            DomainOpsResultSubmitPolicy.normalizeSha256(request.packageSha256(), "packageSha256");
        }
        String submittedAttemptId = trimToMax(request.connectorAttemptId(), 128);
        if (submittedAttemptId != null
                && stored.getConnectorAttemptId() != null
                && !stored.getConnectorAttemptId().equals(submittedAttemptId)) {
            return new TxRejected(new ResponseStatusException(HttpStatus.CONFLICT,
                    "Domain ops connector attempt id does not match the dispatched package."));
        }

        Map<String, Object> executorResult = DomainOpsResultSubmitPolicy.normalize(request.result());
        Object resultPackageSha256 = executorResult.get("packageSha256");
        if (expectedPackageSha256 != null
                && resultPackageSha256 instanceof String resultPackage
                && !expectedPackageSha256.equals(resultPackage)) {
            return new TxRejected(new ResponseStatusException(HttpStatus.CONFLICT,
                    "Domain ops result package digest does not match the dispatched package."));
        }
        Map<String, Object> redactedResult = new LinkedHashMap<>(
                stored.getRedactedResult() == null ? Map.of() : stored.getRedactedResult());
        redactedResult.put("executorResult", executorResult);
        redactedResult.put("executorResultSubmittedAt", now.toString());
        redactedResult.put("executorResultStatus", terminalStatus.name());

        String defaultReason = terminalStatus == DomainOpsStatus.SUCCEEDED
                ? "executor-result-succeeded"
                : "executor-result-failed";
        String reasonCode = safeReasonCode(request.reasonCode(), defaultReason);
        stored.markConnectorResult(
                terminalStatus,
                reasonCode,
                stored.getConnectorName(),
                stored.getConnectorAttemptId(),
                Map.copyOf(redactedResult),
                now);
        stored = requestRepository.saveAndFlush(stored);
        auditStoredRequest(stored, device, eventTypeFor(stored.getState()), actionFor(stored.getState()),
                stored.getCredentialRef() != null,
                stored.getCredentialRefHash() != null);
        return new TxReplay(toResult(stored));
    }

    private ReasonResolution normalizeRequiredReason(AdminTenantContext context,
                                                     EndpointDevice device,
                                                     CreateDomainOpsRequest request) {
        String reason = normalizeReason(request.reason());
        if (reason == null) {
            auditDenied(context, device, request, "reason-required");
            return new ReasonResolution(null, new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain operation reason is required."));
        }
        if (reason.length() > 512) {
            auditDenied(context, device, request, "reason-too-long");
            return new ReasonResolution(null, new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain operation reason must be at most 512 characters."));
        }
        return new ReasonResolution(reason, null);
    }

    private CredentialRefResolution normalizeCredentialRefOrPersistDenied(AdminTenantContext context,
                                                                          EndpointDevice device,
                                                                          CreateDomainOpsRequest request,
                                                                          DomainOpsOperation operation,
                                                                          String reason,
                                                                          String idempotencyKeyHash,
                                                                          Duration ttl,
                                                                          Instant requestedAt,
                                                                          Instant expiresAt) {
        try {
            return new CredentialRefResolution(
                    DomainOpsCredentialRefPolicy.normalizeRequired(request.credentialRef()),
                    null);
        } catch (ResponseStatusException ex) {
            String reasonCode = request.credentialRef() == null || request.credentialRef().trim().isEmpty()
                    ? "credential-ref-required"
                    : "credential-ref-invalid";
            EndpointDomainOpsRequest denied = newRequest(
                    context,
                    device,
                    operation,
                    reason,
                    idempotencyKeyHash,
                    null,
                    null,
                    Map.of(),
                    ttl,
                    requestedAt,
                    expiresAt);
            denied.markDenied(reasonCode, clock.instant());
            denied = requestRepository.saveAndFlush(denied);
            auditStoredRequest(denied, device, EVENT_TYPE_DENIED, "domain-ops.denied",
                    request.credentialRef() != null && !request.credentialRef().trim().isEmpty(), false);
            return new CredentialRefResolution(null, ex);
        }
    }

    private PayloadResolution normalizePayload(AdminTenantContext context,
                                               EndpointDevice device,
                                               CreateDomainOpsRequest request,
                                               DomainOpsOperation operation) {
        try {
            return new PayloadResolution(
                    DomainOpsOperationPayloadPolicy.normalize(operation, request.payload()),
                    null);
        } catch (ResponseStatusException ex) {
            auditDenied(context, device, request, "payload-invalid");
            return new PayloadResolution(null, ex);
        }
    }

    private EndpointDomainOpsRequest newRequest(AdminTenantContext context,
                                                EndpointDevice device,
                                                DomainOpsOperation operation,
                                                String reason,
                                                String idempotencyKeyHash,
                                                String credentialRef,
                                                String credentialRefHash,
                                                Map<String, Object> operationPayload,
                                                Duration ttl,
                                                Instant requestedAt,
                                                Instant expiresAt) {
        EndpointDomainOpsRequest stored = new EndpointDomainOpsRequest();
        stored.setId(UUID.randomUUID());
        stored.setTenantId(context.tenantId());
        stored.setOrgId(context.tenantId());
        stored.setDeviceId(device.getId());
        stored.setOperation(operation);
        stored.setReason(reason);
        stored.setIdempotencyKeyHash(idempotencyKeyHash);
        stored.setCredentialRef(credentialRef);
        stored.setCredentialRefHash(credentialRefHash);
        stored.setOperationPayload(operationPayload);
        stored.setRequestedBy(context.subject());
        stored.setTtlSeconds(ttl.toSeconds());
        stored.setRequestedAt(requestedAt);
        stored.setExpiresAt(expiresAt);
        stored.setStateUpdatedAt(requestedAt);
        stored.setRedactedResult(Map.of());
        return stored;
    }

    private DomainOpsConnectorDispatchResult dispatch(DispatchPlan plan) {
        try {
            return connector.dispatch(new DomainOpsConnectorDispatchRequest(
                    plan.requestId(),
                    plan.tenantId(),
                    plan.deviceId(),
                    plan.hostname(),
                    plan.domainName(),
                    plan.operation(),
                    plan.operationPayload(),
                    plan.credentialRef(),
                    plan.expiresAt(),
                    plan.reason()));
        } catch (RuntimeException ex) {
            return new DomainOpsConnectorDispatchResult(
                    DomainOpsStatus.FAILED,
                    "connector-exception",
                    null,
                    Map.of("exceptionClass", ex.getClass().getName()));
        }
    }

    private void auditStoredRequest(EndpointDomainOpsRequest stored,
                                    EndpointDevice device,
                                    String eventType,
                                    String action,
                                    boolean credentialRefPresent,
                                    boolean credentialRefAccepted) {
        DomainOpsResult result = toResult(stored);
        auditService.record(
                stored.getTenantId(),
                device,
                null,
                eventType,
                action,
                stored.getRequestedBy(),
                stored.getId().toString(),
                metadata(result,
                        stored.getReason(),
                        stored.getIdempotencyKeyHash(),
                        stored.getCredentialRefHash(),
                        stored.getOperationPayload(),
                        credentialRefPresent,
                        credentialRefAccepted),
                null,
                null);
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
        Duration ttl = resolveTtl(request == null ? null : request.ttlSeconds());
        Instant createdAt = clock.instant();
        DomainOpsResult result = new DomainOpsResult(
                UUID.randomUUID(),
                context.tenantId(),
                requestedDeviceId,
                safeOperationName(request == null ? null : request.operation()),
                DomainOpsStatus.DENIED,
                reasonCode,
                ttl.toSeconds(),
                context.subject(),
                createdAt,
                createdAt.plus(ttl),
                null,
                null);
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
                        sha256OrNull(request == null ? null : request.idempotencyKey()),
                        null,
                        safePayloadShape(request == null ? null : request.payload()),
                        request != null && request.credentialRef() != null && !request.credentialRef().trim().isEmpty(),
                        false),
                null,
                null);
        return result;
    }

    private DomainOpsResult toResult(EndpointDomainOpsRequest stored) {
        return new DomainOpsResult(
                stored.getId(),
                stored.getTenantId(),
                stored.getDeviceId(),
                stored.getOperation().name(),
                stored.getState(),
                stored.getReasonCode(),
                stored.getTtlSeconds(),
                stored.getRequestedBy(),
                stored.getRequestedAt(),
                stored.getExpiresAt(),
                stored.getConnectorName(),
                stored.getConnectorAttemptId());
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
        return operation.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private String safeConnectorName(String value) {
        String normalized = trimToMax(value, 128);
        return normalized == null ? "unknown" : normalized;
    }

    private DomainOpsStatus safeDispatchStatus(DomainOpsConnectorDispatchResult result) {
        if (result == null) {
            return DomainOpsStatus.FAILED;
        }
        if (result.status() == DomainOpsStatus.DISPATCHED
                || result.status() == DomainOpsStatus.SUCCEEDED
                || result.status() == DomainOpsStatus.FAILED) {
            return result.status();
        }
        return DomainOpsStatus.FAILED;
    }

    private DomainOpsStatus parseSubmittedStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domain ops result status is required.");
        }
        try {
            DomainOpsStatus status = DomainOpsStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (status == DomainOpsStatus.SUCCEEDED || status == DomainOpsStatus.FAILED) {
                return status;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to the consistent 400 below.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Domain ops result status must be SUCCEEDED or FAILED.");
    }

    private String expectedPackageSha256(EndpointDomainOpsRequest stored) {
        Map<String, Object> result = stored.getRedactedResult();
        if (result == null) {
            return null;
        }
        Object value = result.get("packageSha256");
        if (value instanceof String sha && !sha.isBlank()) {
            return DomainOpsResultSubmitPolicy.normalizeSha256(sha, "stored packageSha256");
        }
        return null;
    }

    private String safeReasonCode(String value, String fallback) {
        String normalized = trimToMax(value, 128);
        if (normalized == null) {
            return fallback;
        }
        normalized = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._:-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? fallback : normalized;
    }

    private String eventTypeFor(DomainOpsStatus status) {
        return switch (status) {
            case DISPATCHED -> EVENT_TYPE_DISPATCHED;
            case SUCCEEDED -> EVENT_TYPE_SUCCEEDED;
            case FAILED -> EVENT_TYPE_FAILED;
            case EXPIRED -> EVENT_TYPE_EXPIRED;
            case DENIED -> EVENT_TYPE_DENIED;
            case ACCEPTED, PENDING_DISPATCH -> EVENT_TYPE_REQUESTED;
        };
    }

    private String actionFor(DomainOpsStatus status) {
        return switch (status) {
            case DISPATCHED -> "domain-ops.dispatched";
            case SUCCEEDED -> "domain-ops.succeeded";
            case FAILED -> "domain-ops.failed";
            case EXPIRED -> "domain-ops.expired";
            case DENIED -> "domain-ops.denied";
            case ACCEPTED, PENDING_DISPATCH -> "domain-ops.requested";
        };
    }

    private Map<String, Object> metadata(DomainOpsResult result,
                                         String reason,
                                         String idempotencyKeyHash,
                                         String credentialRefHash,
                                         Map<String, Object> operationPayload,
                                         boolean credentialRefPresent,
                                         boolean credentialRefAccepted) {
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
        metadata.put("idempotencyKeyHash", idempotencyKeyHash);
        metadata.put("credentialRefPresent", credentialRefPresent);
        metadata.put("credentialRefAccepted", credentialRefAccepted);
        metadata.put("credentialRefHash", credentialRefHash);
        metadata.put("operationPayloadPresent", operationPayload != null && !operationPayload.isEmpty());
        metadata.put("operationPayloadFieldCount", operationPayload == null ? 0 : operationPayload.size());
        metadata.put("expiresAt", result.expiresAt() == null ? null : result.expiresAt().toString());
        metadata.put("connectorName", result.connectorName());
        metadata.put("connectorAttemptId", result.connectorAttemptId());
        metadata.put("contract", CONTRACT);
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

    private String trimToMax(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private Map<String, Object> safePayloadShape(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> shape = new LinkedHashMap<>();
        int index = 0;
        for (String ignored : payload.keySet()) {
            shape.put("field" + index, true);
            index++;
        }
        return shape;
    }

    private interface TxOutcome { }

    private record TxAccepted(DispatchPlan plan) implements TxOutcome { }

    private record TxReplay(DomainOpsResult result) implements TxOutcome { }

    private record TxRejected(ResponseStatusException exception) implements TxOutcome { }

    private record ReasonResolution(String value, ResponseStatusException rejection) { }

    private record CredentialRefResolution(String value, ResponseStatusException rejection) { }

    private record PayloadResolution(Map<String, Object> value, ResponseStatusException rejection) { }

    private record DispatchPlan(UUID requestId,
                                UUID tenantId,
                                UUID deviceId,
                                String hostname,
                                String domainName,
                                DomainOpsOperation operation,
                                Map<String, Object> operationPayload,
                                String credentialRef,
                                Instant expiresAt,
                                String reason) { }
}
