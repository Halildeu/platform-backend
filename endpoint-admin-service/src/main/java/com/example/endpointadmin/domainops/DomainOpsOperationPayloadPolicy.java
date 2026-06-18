package com.example.endpointadmin.domainops;

import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

public final class DomainOpsOperationPayloadPolicy {

    private static final int MAX_TARGET_COMPUTERS = 10;
    private static final Pattern SHA256 = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final Pattern HOSTNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]{0,62}$");
    private static final Pattern SIMPLE_AD_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,126}$");
    private static final Pattern PILOT_OU_DN = Pattern.compile(
            "(?i)^OU=[A-Za-z0-9 ._-]+(,OU=[A-Za-z0-9 ._-]+)*,DC=[A-Za-z0-9-]+(,DC=[A-Za-z0-9-]+)+$");
    private static final Pattern SAFE_STRING_VALUE = Pattern.compile("^[^\\r\\n\\u0000;&|<>`$]{1,512}$");
    private static final Set<String> FORBIDDEN_KEY_PARTS = Set.of(
            "password",
            "token",
            "secret",
            "private",
            "credential",
            "command",
            "script",
            "powershell",
            "cmd",
            "args");
    private static final Set<String> ARTIFACT_HOST_ALLOWLIST = Set.of(
            "github.com",
            "testai.acik.com",
            "ai.acik.com");
    private static final Set<String> GPO_MSI_FIELDS = Set.of(
            "deploymentMethod",
            "artifactVersion",
            "artifactUrl",
            "artifactSha256",
            "pilotOu",
            "pilotGroup",
            "gpoName",
            "targetComputers",
            "rollbackStrategy");
    private static final Set<String> COLLECTOR_FIELDS = Set.of(
            "expectedApiHost",
            "expectedMsiSha256",
            "targetComputers",
            "includeGpResultHtml",
            "includeServiceStatus",
            "includeAgentLogTail",
            "restartServiceBeforeCollect");

    private DomainOpsOperationPayloadPolicy() {
    }

    public static Map<String, Object> normalize(DomainOpsOperation operation,
                                                Map<String, Object> rawPayload) {
        Map<String, Object> payload = rawPayload == null ? Map.of() : rawPayload;
        rejectForbiddenKeys(payload);
        return switch (operation) {
            case DOMAIN_SECURE_CHANNEL_VERIFY, GPO_FORCE_REFRESH, CERT_AUTOENROLL_PULSE ->
                    normalizeEmptyPayload(payload, operation);
            case ENDPOINT_AGENT_GPO_MSI_DEPLOYMENT -> normalizeGpoMsiPayload(payload);
            case ENDPOINT_AGENT_ROLLOUT_COLLECTOR -> normalizeCollectorPayload(payload);
        };
    }

    private static Map<String, Object> normalizeEmptyPayload(Map<String, Object> payload,
                                                             DomainOpsOperation operation) {
        if (!payload.isEmpty()) {
            throw invalid("Payload is not supported for " + operation.name() + ".");
        }
        return Map.of();
    }

    private static Map<String, Object> normalizeGpoMsiPayload(Map<String, Object> payload) {
        requireOnlyFields(payload, GPO_MSI_FIELDS);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("deploymentMethod", requireExact(payload, "deploymentMethod", "gpo-msi"));
        normalized.put("artifactVersion", requireSafeString(payload, "artifactVersion", 64));
        normalized.put("artifactUrl", requireArtifactUrl(payload, "artifactUrl"));
        normalized.put("artifactSha256", requireSha256(payload, "artifactSha256"));
        normalized.put("pilotOu", requirePilotOu(payload, "pilotOu"));
        normalized.put("pilotGroup", requireSimpleAdName(payload, "pilotGroup"));
        normalized.put("gpoName", requireSimpleAdName(payload, "gpoName"));
        normalized.put("targetComputers", requireTargetComputers(payload, "targetComputers"));
        normalized.put("rollbackStrategy", optionalEnum(payload, "rollbackStrategy",
                Set.of("gpo-unlink-or-security-filter", "security-filter-remove")));
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> normalizeCollectorPayload(Map<String, Object> payload) {
        requireOnlyFields(payload, COLLECTOR_FIELDS);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("expectedApiHost", requireHostname(payload, "expectedApiHost"));
        normalized.put("expectedMsiSha256", requireSha256(payload, "expectedMsiSha256"));
        normalized.put("targetComputers", requireTargetComputers(payload, "targetComputers"));
        normalized.put("includeGpResultHtml", requireBoolean(payload, "includeGpResultHtml"));
        normalized.put("includeServiceStatus", requireBoolean(payload, "includeServiceStatus"));
        normalized.put("includeAgentLogTail", requireBoolean(payload, "includeAgentLogTail"));
        normalized.put("restartServiceBeforeCollect", requireBoolean(payload, "restartServiceBeforeCollect"));
        return Map.copyOf(normalized);
    }

    private static void requireOnlyFields(Map<String, Object> payload, Set<String> allowedFields) {
        if (payload.isEmpty()) {
            throw invalid("Operation payload is required.");
        }
        for (String key : payload.keySet()) {
            if (!allowedFields.contains(key)) {
                throw invalid("Operation payload contains unsupported fields.");
            }
        }
    }

    private static void rejectForbiddenKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String normalizedKey = key.toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN_KEY_PARTS) {
                    if (normalizedKey.contains(forbidden)) {
                        throw invalid("Operation payload contains forbidden fields.");
                    }
                }
                rejectForbiddenKeys(entry.getValue());
            }
            return;
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                rejectForbiddenKeys(item);
            }
        }
    }

    private static String requireExact(Map<String, Object> payload, String field, String expected) {
        String value = requireSafeString(payload, field, 64);
        if (!expected.equals(value)) {
            throw invalid("Operation payload field " + field + " must be " + expected + ".");
        }
        return value;
    }

    private static String requireArtifactUrl(Map<String, Object> payload, String field) {
        String value = requireSafeString(payload, field, 512);
        try {
            URI uri = new URI(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || !ARTIFACT_HOST_ALLOWLIST.contains(host)
                    || !path.endsWith(".msi")) {
                throw invalid("Operation payload field " + field + " must be an allowlisted HTTPS MSI URL.");
            }
            return uri.toString();
        } catch (URISyntaxException ex) {
            throw invalid("Operation payload field " + field + " must be a valid URI.");
        }
    }

    private static String requireSha256(Map<String, Object> payload, String field) {
        String value = requireSafeString(payload, field, 64);
        if (!SHA256.matcher(value).matches()) {
            throw invalid("Operation payload field " + field + " must be a SHA-256 hex digest.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String requirePilotOu(Map<String, Object> payload, String field) {
        String value = requireSafeString(payload, field, 256);
        if (!PILOT_OU_DN.matcher(value).matches()) {
            throw invalid("Operation payload field " + field + " must be a pilot OU distinguished name.");
        }
        return value;
    }

    private static String requireSimpleAdName(Map<String, Object> payload, String field) {
        String value = requireSafeString(payload, field, 128);
        if (!SIMPLE_AD_NAME.matcher(value).matches()) {
            throw invalid("Operation payload field " + field + " must be a simple AD object name.");
        }
        return value;
    }

    private static String requireHostname(Map<String, Object> payload, String field) {
        String value = requireSafeString(payload, field, 253).toLowerCase(Locale.ROOT);
        if (!HOSTNAME.matcher(value).matches() || value.contains("..")) {
            throw invalid("Operation payload field " + field + " must be a hostname.");
        }
        return value;
    }

    private static List<String> requireTargetComputers(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof List<?> rawTargets)) {
            throw invalid("Operation payload field " + field + " must be an array.");
        }
        if (rawTargets.isEmpty() || rawTargets.size() > MAX_TARGET_COMPUTERS) {
            throw invalid("Operation payload field " + field + " must contain 1-" + MAX_TARGET_COMPUTERS + " computers.");
        }
        List<String> targets = new ArrayList<>();
        for (Object item : rawTargets) {
            if (!(item instanceof String raw)) {
                throw invalid("Operation payload field " + field + " must contain hostnames.");
            }
            String target = normalizeSafeString(raw, field, 64).toUpperCase(Locale.ROOT);
            if (!HOSTNAME.matcher(target).matches() || target.contains("..")) {
                throw invalid("Operation payload field " + field + " contains an invalid hostname.");
            }
            targets.add(target);
        }
        return List.copyOf(targets);
    }

    private static boolean requireBoolean(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof Boolean bool)) {
            throw invalid("Operation payload field " + field + " must be boolean.");
        }
        return bool;
    }

    private static String optionalEnum(Map<String, Object> payload, String field, Set<String> allowed) {
        Object value = payload.get(field);
        if (value == null) {
            return allowed.iterator().next();
        }
        String normalized = requireSafeString(payload, field, 64);
        if (!allowed.contains(normalized)) {
            throw invalid("Operation payload field " + field + " is not allowed.");
        }
        return normalized;
    }

    private static String requireSafeString(Map<String, Object> payload, String field, int maxLength) {
        Object value = payload.get(field);
        if (!(value instanceof String raw)) {
            throw invalid("Operation payload field " + field + " is required.");
        }
        return normalizeSafeString(raw, field, maxLength);
    }

    private static String normalizeSafeString(String raw, String field, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > maxLength || !SAFE_STRING_VALUE.matcher(value).matches()) {
            throw invalid("Operation payload field " + field + " is invalid.");
        }
        return value;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }
}
