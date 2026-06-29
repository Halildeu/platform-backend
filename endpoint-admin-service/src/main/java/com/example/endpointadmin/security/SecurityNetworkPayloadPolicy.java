package com.example.endpointadmin.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict pre-persist validator for the structured security/network block carried
 * by {@code COLLECT_INVENTORY}. This is the #527 EDR_NETWORK source boundary:
 * the backend accepts only pre-redacted, allowlisted block events and rejects raw
 * hostnames, IPs, process paths, URLs, tokens, or free-text evidence before the
 * command result row is persisted.
 */
@Component
public class SecurityNetworkPayloadPolicy {

    private static final Logger log = LoggerFactory.getLogger(SecurityNetworkPayloadPolicy.class);

    public static final int PROBE_DURATION_MAX_MS = 120000;
    public static final int EVENTS_MAX = 20;
    public static final int PROBE_ERRORS_MAX = 16;
    public static final int SUMMARY_MAX_LEN = 200;

    private static final Set<Integer> ACCEPTED_SCHEMA_VERSIONS = Set.of(1);

    private static final Set<String> TOP_ALLOWED_KEYS = Set.of(
            "schemaVersion", "supported", "probeComplete", "events", "probeErrors", "probeDurationMs"
    );
    private static final Set<String> TOP_REQUIRED_KEYS = Set.of(
            "schemaVersion", "supported", "probeComplete", "events", "probeDurationMs"
    );
    private static final Set<String> EVENT_ALLOWED_KEYS = Set.of(
            "networkSegmentId", "edrVendor", "blockedProcessHashPrefix",
            "blockedDestination", "firewallRuleId", "lastSuccessfulContactAt", "observedAt"
    );
    private static final Set<String> PROBE_ERROR_ALLOWED_KEYS = Set.of("code", "summary");
    private static final Set<String> PROBE_ERROR_CODE_ENUM = Set.of(
            "UNSUPPORTED_PLATFORM", "EVENT_LOG_UNAVAILABLE", "ACCESS_DENIED",
            "PROBE_TIMEOUT", "PROBE_FAILED", "NO_EVIDENCE", "EVENTS_TRUNCATED"
    );
    private static final Set<String> FORBIDDEN_TOP_KEYS = Set.of(
            "apiURL", "apiUrl", "host", "hostname", "credentialId", "token",
            "apiKey", "bearer", "authorization", "cookie", "session", "secret",
            "password", "processPath", "applicationPath", "destinationIp", "destinationHost"
    );

    private static final Pattern HASH_HEX = Pattern.compile("^[0-9a-f]{8,64}$");
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.:-]{1,127}$");
    private static final Pattern VENDOR = Pattern.compile("^[a-z][a-z0-9_.:-]{1,63}$");
    private static final Pattern DESTINATION = Pattern.compile(
            "^(dest-sha256-[0-9a-f]{16,64}|destination-sha256-[0-9a-f]{16,64}|blocked-egress-[A-Za-z0-9_.:-]{1,96})$");
    private static final Pattern CONTROL_CHAR_RE = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern RAW_VALUE_DENYLIST = Pattern.compile(
            "(?i)(https?://|bearer\\s|authorization:|x-api-key|api[_-]?key|cookie:|session=|"
                    + "password=|secret=|token=|private[_-]?key|client[_-]?secret|"
                    + "\\.(?:com|net|org|io|local|dev|cloud|internal|corp|azure)\\b|"
                    + "[A-Z]:\\\\|\\\\Users\\\\|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|"
                    + "::|(?:[0-9a-f]{1,4}:){2,})");

    private final ObjectMapper canonicalMapper;

    public SecurityNetworkPayloadPolicy() {
        ObjectMapper m = new ObjectMapper();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.canonicalMapper = m;
    }

    public static boolean hasSecurityNetworkBlock(Map<String, Object> details) {
        if (details == null) {
            return false;
        }
        Object inventory = details.get("inventory");
        if (inventory instanceof Map<?, ?> inv && inv.get("securityNetwork") instanceof Map<?, ?>) {
            return true;
        }
        return details.get("securityNetwork") instanceof Map<?, ?>;
    }

    public Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Object inventoryNode = details.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            validatePresentNode(inventoryMap.get("securityNetwork"), "$.inventory.securityNetwork");
        }
        validatePresentNode(details.get("securityNetwork"), "$.securityNetwork");
        return details;
    }

    @SuppressWarnings("unchecked")
    private void validatePresentNode(Object node, String path) {
        if (node == null) {
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "securityNetwork block at " + path + " must be a Map or absent (got "
                            + node.getClass().getName() + ")");
        }
        projectAndHash((Map<String, Object>) map);
    }

    public Projection projectAndHash(Map<String, Object> block) {
        if (block == null) {
            throw new IllegalArgumentException("securityNetwork block is null.");
        }
        for (String key : block.keySet()) {
            if (FORBIDDEN_TOP_KEYS.contains(key)) {
                log.warn("securityNetwork payload rejected: forbidden key '{}' present.", key);
                throw new IllegalArgumentException("securityNetwork payload contains forbidden key: " + key);
            }
            if (!TOP_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("securityNetwork payload contains unknown top-level key: " + key);
            }
        }
        for (String required : TOP_REQUIRED_KEYS) {
            if (!block.containsKey(required)) {
                throw new IllegalArgumentException("securityNetwork payload missing required key: " + required);
            }
        }

        int schemaVersion = readInt(block, "schemaVersion");
        if (!ACCEPTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
            throw new IllegalArgumentException("securityNetwork unsupported schemaVersion: " + schemaVersion);
        }
        boolean supported = readBool(block, "supported");
        boolean probeComplete = readBool(block, "probeComplete");
        int probeDurationMs = readInt(block, "probeDurationMs");
        if (probeDurationMs < 0 || probeDurationMs > PROBE_DURATION_MAX_MS) {
            throw new IllegalArgumentException("securityNetwork probeDurationMs out of range [0.."
                    + PROBE_DURATION_MAX_MS + "]: " + probeDurationMs);
        }

        List<EventProjection> events = projectEvents(block.get("events"), supported);
        List<ProbeErrorProjection> probeErrors = projectProbeErrors(
                block.containsKey("probeErrors") ? block.get("probeErrors") : null,
                block.containsKey("probeErrors"));

        Map<String, Object> hashMap = new LinkedHashMap<>();
        hashMap.put("schemaVersion", schemaVersion);
        hashMap.put("supported", supported);
        hashMap.put("probeComplete", probeComplete);
        hashMap.put("probeDurationMs", probeDurationMs);
        List<Map<String, Object>> eventMaps = new ArrayList<>(events.size());
        for (EventProjection e : events) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("networkSegmentId", e.networkSegmentId());
            em.put("edrVendor", e.edrVendor());
            em.put("blockedProcessHashPrefix", e.blockedProcessHashPrefix());
            em.put("blockedDestination", e.blockedDestination());
            em.put("firewallRuleId", e.firewallRuleId());
            em.put("lastSuccessfulContactAt", e.lastSuccessfulContactAt());
            em.put("observedAt", e.observedAt());
            eventMaps.add(em);
        }
        hashMap.put("events", eventMaps);
        List<Map<String, Object>> errorMaps = new ArrayList<>(probeErrors.size());
        for (ProbeErrorProjection pe : probeErrors) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("code", pe.code());
            pm.put("summary", pe.summary());
            errorMaps.add(pm);
        }
        hashMap.put("probeErrors", errorMaps);

        return new Projection(schemaVersion, supported, probeComplete,
                probeDurationMs, events, probeErrors, sha256Hex(hashMap));
    }

    private List<EventProjection> projectEvents(Object raw, boolean supported) {
        if (raw == null) {
            throw new IllegalArgumentException("securityNetwork events list is null (must be array).");
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException("securityNetwork events must be a List.");
        }
        if (rawList.size() > EVENTS_MAX) {
            throw new IllegalArgumentException("securityNetwork events exceeds cap of " + EVENTS_MAX);
        }
        if (!supported && !rawList.isEmpty()) {
            throw new IllegalArgumentException("securityNetwork unsupported payload must not carry events.");
        }
        List<EventProjection> out = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("securityNetwork events[" + i + "] must be a Map.");
            }
            out.add(projectEvent(i, map));
        }
        return List.copyOf(out);
    }

    private EventProjection projectEvent(int rowOrdinal, Map<?, ?> map) {
        for (Object keyObj : map.keySet()) {
            if (!(keyObj instanceof String key) || !EVENT_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("securityNetwork events[" + rowOrdinal
                        + "] contains unknown key: " + keyObj);
            }
        }
        for (String required : EVENT_ALLOWED_KEYS) {
            if (!map.containsKey(required)) {
                throw new IllegalArgumentException("securityNetwork events[" + rowOrdinal
                        + "] missing required key: " + required);
            }
        }
        String networkSegmentId = nullableToken(map.get("networkSegmentId"), "networkSegmentId");
        String edrVendor = requiredVendor(map.get("edrVendor"));
        String processHash = nullableHash(map.get("blockedProcessHashPrefix"), "blockedProcessHashPrefix");
        String blockedDestination = nullableDestination(map.get("blockedDestination"));
        String firewallRuleId = nullableToken(map.get("firewallRuleId"), "firewallRuleId");
        String lastSuccess = nullableDateTime(map.get("lastSuccessfulContactAt"), "lastSuccessfulContactAt");
        String observedAt = requiredDateTime(map.get("observedAt"), "observedAt");
        if (processHash == null && blockedDestination == null && firewallRuleId == null) {
            throw new IllegalArgumentException("securityNetwork events[" + rowOrdinal
                    + "] must carry at least one blocked-process, destination, or rule signal.");
        }
        return new EventProjection(rowOrdinal, networkSegmentId, edrVendor, processHash,
                blockedDestination, firewallRuleId, lastSuccess, observedAt);
    }

    private List<ProbeErrorProjection> projectProbeErrors(Object raw, boolean explicitlyPresent) {
        if (raw == null) {
            if (explicitlyPresent) {
                throw new IllegalArgumentException("securityNetwork probeErrors must be omitted or a List, not null.");
            }
            return List.of();
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException("securityNetwork probeErrors must be a List.");
        }
        if (rawList.size() > PROBE_ERRORS_MAX) {
            throw new IllegalArgumentException("securityNetwork probeErrors exceeds cap of " + PROBE_ERRORS_MAX);
        }
        List<ProbeErrorProjection> out = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("securityNetwork probeErrors[" + i + "] must be a Map.");
            }
            for (Object keyObj : map.keySet()) {
                if (!(keyObj instanceof String key) || !PROBE_ERROR_ALLOWED_KEYS.contains(key)) {
                    throw new IllegalArgumentException("securityNetwork probeErrors[" + i
                            + "] contains unknown key: " + keyObj);
                }
            }
            String code = requiredString(map.get("code"), "probeErrors[" + i + "].code");
            if (!PROBE_ERROR_CODE_ENUM.contains(code)) {
                throw new IllegalArgumentException("securityNetwork probeErrors[" + i
                        + "].code must be in " + PROBE_ERROR_CODE_ENUM);
            }
            String summary = nullableSummary(map.get("summary"), "probeErrors[" + i + "].summary");
            out.add(new ProbeErrorProjection(i, code, summary));
        }
        return List.copyOf(out);
    }

    private static int readInt(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw instanceof Integer i) {
            return i;
        }
        if (raw instanceof Number n && Math.rint(n.doubleValue()) == n.doubleValue()) {
            return n.intValue();
        }
        throw new IllegalArgumentException("securityNetwork " + key + " must be an integer.");
    }

    private static boolean readBool(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("securityNetwork " + key + " must be a boolean.");
    }

    private static String requiredVendor(Object raw) {
        String value = requiredString(raw, "edrVendor");
        if (!VENDOR.matcher(value).matches()) {
            throw new IllegalArgumentException("securityNetwork edrVendor is not a bounded vendor token.");
        }
        scanRaw("edrVendor", value);
        return value;
    }

    private static String nullableHash(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        String value = requiredString(raw, field);
        if (!HASH_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("securityNetwork " + field + " must match ^[0-9a-f]{8,64}$.");
        }
        return value;
    }

    private static String nullableDestination(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = requiredString(raw, "blockedDestination");
        scanRaw("blockedDestination", value);
        if (!DESTINATION.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "securityNetwork blockedDestination must be a redacted destination marker.");
        }
        return value;
    }

    private static String nullableToken(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        String value = requiredString(raw, field);
        scanRaw(field, value);
        if (!SAFE_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("securityNetwork " + field + " is not a bounded token.");
        }
        return value;
    }

    private static String requiredDateTime(Object raw, String field) {
        String value = requiredString(raw, field);
        parseDateTime(value, field);
        return value;
    }

    private static String nullableDateTime(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        String value = requiredString(raw, field);
        parseDateTime(value, field);
        return value;
    }

    private static void parseDateTime(String value, String field) {
        try {
            OffsetDateTime.parse(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("securityNetwork " + field + " must be ISO-8601 date-time.");
        }
    }

    private static String nullableSummary(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        String value = requiredString(raw, field);
        if (value.length() > SUMMARY_MAX_LEN) {
            throw new IllegalArgumentException("securityNetwork " + field + " exceeds length cap.");
        }
        scanRaw(field, value);
        return value;
    }

    private static String requiredString(Object raw, String field) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("securityNetwork " + field + " must be a non-empty string.");
        }
        if (CONTROL_CHAR_RE.matcher(value).find()) {
            throw new IllegalArgumentException("securityNetwork " + field + " contains control character.");
        }
        return value;
    }

    private static void scanRaw(String field, String value) {
        if (RAW_VALUE_DENYLIST.matcher(value).find()) {
            throw new IllegalArgumentException("securityNetwork " + field
                    + " contains a forbidden raw identifier/secret marker.");
        }
    }

    private String sha256Hex(Object canonical) {
        try {
            byte[] bytes = canonicalMapper.writeValueAsBytes(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("securityNetwork canonical hash failed", ex);
        }
    }

    public record Projection(int schemaVersion,
                             boolean supported,
                             boolean probeComplete,
                             int probeDurationMs,
                             List<EventProjection> events,
                             List<ProbeErrorProjection> probeErrors,
                             String payloadHashSha256) {
    }

    public record EventProjection(int rowOrdinal,
                                  String networkSegmentId,
                                  String edrVendor,
                                  String blockedProcessHashPrefix,
                                  String blockedDestination,
                                  String firewallRuleId,
                                  String lastSuccessfulContactAt,
                                  String observedAt) {
    }

    public record ProbeErrorProjection(int rowOrdinal, String code, String summary) {
    }
}
