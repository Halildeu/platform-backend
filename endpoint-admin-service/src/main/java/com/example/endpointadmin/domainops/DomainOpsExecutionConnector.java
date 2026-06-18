package com.example.endpointadmin.domainops;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public class DomainOpsExecutionConnector implements DomainOpsConnector {

    private static final String NAME = "domain-ops-execution-connector";

    private final DomainOpsExecutionConnectorProperties properties;
    private final Clock clock;

    public DomainOpsExecutionConnector(DomainOpsExecutionConnectorProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DomainOpsConnectorDispatchResult dispatch(DomainOpsConnectorDispatchRequest request) {
        boolean planOnlyMode = DomainOpsExecutionConnectorProperties.DISPATCH_PLAN_ONLY_MODE.equals(properties.mode());
        boolean executionPackageMode = DomainOpsExecutionConnectorProperties.EXECUTION_PACKAGE_MODE.equals(properties.mode());
        if (!planOnlyMode && !executionPackageMode) {
            return DomainOpsConnectorDispatchResult.failed("connector-mode-unsupported");
        }
        if (request == null || request.expiresAt() == null || !Instant.now(clock).isBefore(request.expiresAt())) {
            return DomainOpsConnectorDispatchResult.failed("dispatch-window-expired");
        }
        if (!credentialRefAllowed(request.credentialRef())) {
            return DomainOpsConnectorDispatchResult.failed("credential-ref-not-allowed");
        }

        return switch (request.operation()) {
            case ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT -> planOnlyMode
                    ? dispatchGpoMsiDeploymentPlan(request)
                    : dispatchGpoMsiExecutionPackage(request);
            case ENDPOINT_AGENT_ROLLOUT_COLLECTOR -> planOnlyMode
                    ? dispatchRolloutCollectorPlan(request)
                    : dispatchRolloutCollectorExecutionPackage(request);
            case DOMAIN_SECURE_CHANNEL_VERIFY, GPO_FORCE_REFRESH, CERT_AUTOENROLL_PULSE ->
                    DomainOpsConnectorDispatchResult.failed("operation-not-supported-by-execution-connector");
        };
    }

    private DomainOpsConnectorDispatchResult dispatchGpoMsiDeploymentPlan(DomainOpsConnectorDispatchRequest request) {
        Map<String, Object> payload = request.operationPayload();
        URI artifactUri = URI.create(string(payload, "artifactUrl"));
        List<String> targets = targets(payload);
        Map<String, Object> result = new TreeMap<>();
        result.put("artifactHost", artifactUri.getHost());
        result.put("artifactPathSha256", sha256(artifactUri.getPath() == null ? "" : artifactUri.getPath()));
        result.put("artifactSha256", string(payload, "artifactSha256"));
        result.put("artifactVersion", string(payload, "artifactVersion"));
        result.put("connectorMode", properties.mode());
        result.put("deploymentMethod", "gpo-msi");
        result.put("dispatchKind", "GPO_MSI_DEPLOYMENT_PLAN");
        result.put("gpoNameSha256", sha256(string(payload, "gpoName")));
        result.put("pilotGroupSha256", sha256(string(payload, "pilotGroup")));
        result.put("pilotOuSha256", sha256(string(payload, "pilotOu")));
        result.put("requestId", request.requestId().toString());
        result.put("rollbackStrategy", string(payload, "rollbackStrategy"));
        result.put("steps", List.of(
                "VERIFY_MSI_DIGEST",
                "STAGE_MSI_ARTIFACT",
                "ENSURE_GPO_LINK",
                "APPLY_PILOT_SECURITY_FILTER",
                "REQUEST_GPUPDATE",
                "QUEUE_ROLLOUT_COLLECTOR"));
        result.put("targetComputerCount", targets.size());
        result.put("targetComputerSetSha256", sha256(String.join("\n", sorted(targets))));
        result.put("tenantId", request.tenantId().toString());

        return new DomainOpsConnectorDispatchResult(
                DomainOpsStatus.DISPATCHED,
                "gpo-msi-deployment-plan-created",
                attemptId(request.requestId()),
                Map.copyOf(result));
    }

    private DomainOpsConnectorDispatchResult dispatchRolloutCollectorPlan(DomainOpsConnectorDispatchRequest request) {
        Map<String, Object> payload = request.operationPayload();
        List<String> targets = targets(payload);
        List<String> evidenceTypes = new ArrayList<>();
        if (bool(payload, "includeGpResultHtml")) {
            evidenceTypes.add("GPRESULT_HTML");
        }
        if (bool(payload, "includeServiceStatus")) {
            evidenceTypes.add("SERVICE_STATUS");
        }
        if (bool(payload, "includeAgentLogTail")) {
            evidenceTypes.add("AGENT_LOG_TAIL");
        }
        evidenceTypes.sort(Comparator.naturalOrder());

        Map<String, Object> result = new TreeMap<>();
        result.put("connectorMode", properties.mode());
        result.put("dispatchKind", "ROLLOUT_COLLECTOR_PLAN");
        result.put("evidenceTypes", List.copyOf(evidenceTypes));
        result.put("expectedApiHost", string(payload, "expectedApiHost"));
        result.put("expectedMsiSha256", string(payload, "expectedMsiSha256"));
        result.put("requestId", request.requestId().toString());
        result.put("restartServiceBeforeCollect", bool(payload, "restartServiceBeforeCollect"));
        result.put("steps", List.of(
                "VERIFY_MACHINE_CERT_PRESENCE",
                "VERIFY_SERVICE_STATUS",
                "VERIFY_AGENT_CONFIG",
                "COLLECT_REQUESTED_EVIDENCE",
                "UPLOAD_REDACTED_EVIDENCE"));
        result.put("targetComputerCount", targets.size());
        result.put("targetComputerSetSha256", sha256(String.join("\n", sorted(targets))));
        result.put("tenantId", request.tenantId().toString());

        return new DomainOpsConnectorDispatchResult(
                DomainOpsStatus.DISPATCHED,
                "rollout-collector-plan-created",
                attemptId(request.requestId()),
                Map.copyOf(result));
    }

    private DomainOpsConnectorDispatchResult dispatchGpoMsiExecutionPackage(DomainOpsConnectorDispatchRequest request) {
        Map<String, Object> payload = request.operationPayload();
        URI artifactUri = URI.create(string(payload, "artifactUrl"));
        List<String> targets = targets(payload);
        Map<String, Object> result = executionPackageBase(request, payload, targets, "GPO_MSI_EXECUTION_PACKAGE");
        result.put("artifactHost", artifactUri.getHost());
        result.put("artifactPathSha256", sha256(artifactUri.getPath() == null ? "" : artifactUri.getPath()));
        result.put("artifactSha256", string(payload, "artifactSha256"));
        result.put("artifactVersion", string(payload, "artifactVersion"));
        result.put("deploymentMethod", "gpo-msi");
        result.put("expectedEvidenceContract", List.of(
                "MSI_DIGEST_VERIFIED",
                "GPO_LINK_SCOPED_TO_PILOT",
                "PILOT_SECURITY_FILTER_APPLIED",
                "GPUPDATE_REQUESTED_OR_NATURAL_REFRESH_RECORDED",
                "ROLLOUT_COLLECTOR_QUEUED"));
        result.put("executorRequirements", List.of(
                "domain-joined-windows-executor",
                "delegated-gpo-admin-or-gpmc-capability",
                "credential-ref-resolved-outside-backend",
                "no-raw-shell-or-arbitrary-command"));
        result.put("rollbackStrategy", string(payload, "rollbackStrategy"));
        result.put("steps", List.of(
                "VERIFY_MSI_DIGEST",
                "STAGE_MSI_ARTIFACT",
                "ENSURE_GPO_LINK",
                "APPLY_PILOT_SECURITY_FILTER",
                "REQUEST_GPUPDATE",
                "QUEUE_ROLLOUT_COLLECTOR"));
        sealExecutionPackage(result);

        return new DomainOpsConnectorDispatchResult(
                DomainOpsStatus.DISPATCHED,
                "gpo-msi-execution-package-created",
                attemptId(request.requestId()),
                Map.copyOf(result));
    }

    private DomainOpsConnectorDispatchResult dispatchRolloutCollectorExecutionPackage(
            DomainOpsConnectorDispatchRequest request) {
        Map<String, Object> payload = request.operationPayload();
        List<String> targets = targets(payload);
        List<String> evidenceTypes = new ArrayList<>();
        if (bool(payload, "includeGpResultHtml")) {
            evidenceTypes.add("GPRESULT_HTML");
        }
        if (bool(payload, "includeServiceStatus")) {
            evidenceTypes.add("SERVICE_STATUS");
        }
        if (bool(payload, "includeAgentLogTail")) {
            evidenceTypes.add("AGENT_LOG_TAIL");
        }
        evidenceTypes.sort(Comparator.naturalOrder());

        Map<String, Object> result = executionPackageBase(
                request,
                payload,
                targets,
                "ROLLOUT_COLLECTOR_EXECUTION_PACKAGE");
        result.put("evidenceTypes", List.copyOf(evidenceTypes));
        result.put("expectedApiHost", string(payload, "expectedApiHost"));
        result.put("expectedMsiSha256", string(payload, "expectedMsiSha256"));
        result.put("expectedEvidenceContract", List.of(
                "MACHINE_CLIENT_AUTH_CERT_PRESENT",
                "ENDPOINT_AGENT_SERVICE_RUNNING_AUTOMATIC_LOCALSYSTEM",
                "ENDPOINT_AGENT_BINARY_DIGEST_MATCHES_EXPECTED_MSI",
                "MTLS_HOST_REACHABLE_AND_CONFIGURED",
                "REDACTED_GPRESULT_OR_EQUIVALENT_POLICY_EVIDENCE",
                "AGENT_LOG_TAIL_REDACTED"));
        result.put("executorRequirements", List.of(
                "domain-joined-windows-executor",
                "local-admin-or-approved-collector-capability",
                "credential-ref-resolved-outside-backend",
                "no-token-private-key-or-secret-export"));
        result.put("restartServiceBeforeCollect", bool(payload, "restartServiceBeforeCollect"));
        result.put("steps", List.of(
                "VERIFY_MACHINE_CERT_PRESENCE",
                "VERIFY_SERVICE_STATUS",
                "VERIFY_AGENT_CONFIG",
                "COLLECT_REQUESTED_EVIDENCE",
                "UPLOAD_REDACTED_EVIDENCE"));
        sealExecutionPackage(result);

        return new DomainOpsConnectorDispatchResult(
                DomainOpsStatus.DISPATCHED,
                "rollout-collector-execution-package-created",
                attemptId(request.requestId()),
                Map.copyOf(result));
    }

    private Map<String, Object> executionPackageBase(DomainOpsConnectorDispatchRequest request,
                                                     Map<String, Object> payload,
                                                     List<String> targets,
                                                     String dispatchKind) {
        Instant now = Instant.now(clock);
        Map<String, Object> result = new TreeMap<>();
        result.put("connectorMode", properties.mode());
        result.put("credentialRefSha256", sha256(request.credentialRef()));
        result.put("deviceId", request.deviceId().toString());
        result.put("dispatchKind", dispatchKind);
        result.put("expiresAt", request.expiresAt().toString());
        result.put("issuedAt", now.toString());
        result.put("operation", request.operation().name());
        result.put("packageFormat", "domain-ops-execution-package.v1");
        result.put("payloadSha256", sha256(canonical(payload)));
        result.put("requestId", request.requestId().toString());
        result.put("targetComputerCount", targets.size());
        result.put("targetComputerSetSha256", sha256(String.join("\n", sorted(targets))));
        result.put("tenantId", request.tenantId().toString());
        result.put("ttlSeconds", Math.max(0L, Duration.between(now, request.expiresAt()).toSeconds()));
        return result;
    }

    private static void sealExecutionPackage(Map<String, Object> result) {
        result.put("packageSha256", sha256(canonical(result)));
    }

    private boolean credentialRefAllowed(String credentialRef) {
        if (credentialRef == null || credentialRef.isBlank()) {
            return false;
        }
        if (credentialRef.indexOf('\u0000') >= 0
                || credentialRef.chars().anyMatch(Character::isWhitespace)
                || credentialRef.contains("..")
                || credentialRef.contains("\\")
                || credentialRef.contains("//")) {
            return false;
        }
        return properties.allowedCredentialRefPrefixes().stream().anyMatch(credentialRef::startsWith);
    }

    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, entry) -> sorted.put(String.valueOf(key), entry));
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(entry.getKey()).append('=').append(canonical(entry.getValue()));
            }
            return builder.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (Object entry : collection) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(canonical(entry));
            }
            return builder.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static String attemptId(UUID requestId) {
        String compact = requestId.toString().replace("-", "");
        return "domops-" + compact.substring(0, Math.min(16, compact.length()));
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String str)) {
            throw new IllegalArgumentException("normalized payload missing string field");
        }
        return str;
    }

    private static boolean bool(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("normalized payload missing boolean field");
        }
        return bool;
    }

    @SuppressWarnings("unchecked")
    private static List<String> targets(Map<String, Object> payload) {
        Object value = payload.get("targetComputers");
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("normalized payload missing targetComputers field");
        }
        return (List<String>) list;
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
