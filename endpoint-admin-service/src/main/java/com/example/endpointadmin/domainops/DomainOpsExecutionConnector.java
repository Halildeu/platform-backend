package com.example.endpointadmin.domainops;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
        if (!DomainOpsExecutionConnectorProperties.DISPATCH_PLAN_ONLY_MODE.equals(properties.mode())) {
            return DomainOpsConnectorDispatchResult.failed("connector-mode-unsupported");
        }
        if (request == null || request.expiresAt() == null || !Instant.now(clock).isBefore(request.expiresAt())) {
            return DomainOpsConnectorDispatchResult.failed("dispatch-window-expired");
        }
        if (!credentialRefAllowed(request.credentialRef())) {
            return DomainOpsConnectorDispatchResult.failed("credential-ref-not-allowed");
        }

        return switch (request.operation()) {
            case ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT -> dispatchGpoMsiDeployment(request);
            case ENDPOINT_AGENT_ROLLOUT_COLLECTOR -> dispatchRolloutCollector(request);
            case DOMAIN_SECURE_CHANNEL_VERIFY, GPO_FORCE_REFRESH, CERT_AUTOENROLL_PULSE ->
                    DomainOpsConnectorDispatchResult.failed("operation-not-supported-by-execution-connector");
        };
    }

    private DomainOpsConnectorDispatchResult dispatchGpoMsiDeployment(DomainOpsConnectorDispatchRequest request) {
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

    private DomainOpsConnectorDispatchResult dispatchRolloutCollector(DomainOpsConnectorDispatchRequest request) {
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
