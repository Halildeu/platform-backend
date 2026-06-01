package com.example.endpointadmin.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BE — pre-persist sanitizer/validator for the AG-040 startup apps +
 * exposure summary sub-tree of the COLLECT_INVENTORY payload (Faz 22.5,
 * AG-040-be). Mirrors AG-039-be {@link ServicesPayloadPolicy} strict-
 * allowlist pattern + adapts to AG-040-specific invariants per Codex
 * 019e8387 plan iter-1 AGREE.
 *
 * <h3>Top-level contract</h3>
 *
 * <p>7 REQUIRED keys: schemaVersion (=1), supported (bool), probeComplete
 * (bool), rdpEnabled (bool), windowsFirewallEventLogEnabled (bool),
 * startupApps (List), probeDurationMs (int 0..120000). 1 OPTIONAL key
 * with omitempty semantics: probeErrors (List or absent; explicit null
 * REJECT).
 *
 * <h3>StartupApps validation</h3>
 *
 * <p>List size 0..50 (MaxStartupEntries cap). Each entry exactly
 * {@code {name, location, enabled, probeOrigin}}; location MUST be in
 * 10-anchor allowlist; probeOrigin MUST be REGISTRY or SCHEDULED_TASK;
 * name bounded length 1..256 + control-char reject; ordering preserved.
 *
 * <h3>Canonical-form payload hash</h3>
 *
 * <p>INCLUDES every persistable field: schemaVersion, supported,
 * probeComplete, rdpEnabled, windowsFirewallEventLogEnabled,
 * startupApps (full ordered list with all 4 fields), probeErrors
 * (ordered list with code + source + summary), probeDurationMs.
 * EXCLUDES: none.
 *
 * <h3>Type-confusion bypass closed</h3>
 *
 * <p>{@link #sanitize(Map)} wired into the pre-persist chain rejects
 * present-but-non-Map startupExposure blocks. Jackson containsKey vs
 * get-returns-null distinguishes absent (omitempty ACCEPT) vs explicit
 * null (REJECT).
 */
@Component
public class StartupExposurePayloadPolicy {

    private static final Logger log = LoggerFactory.getLogger(StartupExposurePayloadPolicy.class);

    public static final int NAME_MAX_LEN = 256;
    public static final int SUMMARY_MAX_LEN = 200;
    public static final int PROBE_DURATION_MAX_MS = 120000;
    public static final int STARTUP_APPS_MAX = 50;
    public static final int PROBE_ERRORS_MAX = 16;

    /** Bounded Location enum — 10 autorun anchors. Codex 019e8387 plan iter-1 P1 #1. */
    public static final List<String> CANONICAL_LOCATIONS = List.of(
            "HKLM_RUN", "HKLM_RUNONCE", "HKLM_WOW6432_RUN",
            "HKCU_RUN", "HKCU_RUNONCE",
            "STARTUP_FOLDER_COMMON", "STARTUP_FOLDER_USER",
            "TASK_SCHEDULER:ROOT", "TASK_SCHEDULER:MICROSOFT_WINDOWS",
            "TASK_SCHEDULER:CUSTOM"
    );
    public static final Set<String> CANONICAL_LOCATIONS_SET = Set.copyOf(CANONICAL_LOCATIONS);

    public static final Set<String> PROBE_ORIGIN_ENUM = Set.of("REGISTRY", "SCHEDULED_TASK");

    private static final Set<Integer> ACCEPTED_SCHEMA_VERSIONS = Set.of(1);

    private static final Set<String> TOP_ALLOWED_KEYS = Set.of(
            "schemaVersion", "supported", "probeComplete",
            "rdpEnabled", "windowsFirewallEventLogEnabled",
            "startupApps", "probeErrors", "probeDurationMs"
    );

    private static final Set<String> TOP_REQUIRED_KEYS = Set.of(
            "schemaVersion", "supported", "probeComplete",
            "rdpEnabled", "windowsFirewallEventLogEnabled",
            "startupApps", "probeDurationMs"
    );

    private static final Set<String> APP_ALLOWED_KEYS = Set.of(
            "name", "location", "enabled", "probeOrigin"
    );

    private static final Set<String> PROBE_ERROR_ALLOWED_KEYS = Set.of(
            "code", "source", "summary"
    );

    private static final Set<String> PROBE_ERROR_CODE_ENUM = Set.of(
            "UNSUPPORTED_PLATFORM", "REGISTRY_QUERY_FAILED",
            "TASK_SCHEDULER_UNAVAILABLE", "TASK_SCHEDULER_QUERY_FAILED",
            "STARTUP_FOLDER_UNREADABLE",
            "RDP_PROBE_FAILED", "FIREWALL_PROBE_FAILED",
            "ENTRY_CAP_APPLIED", "NO_EVIDENCE"
    );

    private static final Set<String> FORBIDDEN_TOP_KEYS = Set.of(
            "apiURL", "apiUrl", "host", "hostname", "credentialId",
            "token", "apiKey", "bearer", "authorization", "cookie",
            "session", "secret", "password",
            // AG-040-specific forbidden leak vectors.
            "executable", "exe", "fullPath", "path", "command", "commandLine",
            "args", "arguments", "runAs", "account", "workingDirectory",
            "activeSessions", "rdpActiveSessions", "sessionCount"
    );

    private static final Pattern CONTROL_CHAR_RE = Pattern.compile(
            "[\\x00-\\x1F\\x7F]");

    /** AG-038-be SUMMARY_VALUE_DENYLIST_RE reuse — defense-in-depth
     *  redaction guard against URL / Bearer / IP / token in summary. */
    private static final Pattern SUMMARY_VALUE_DENYLIST_RE = Pattern.compile(
            "(?i)(https?://|bearer\\s|authorization:|x-api-key|api[_-]?key|cookie:|session=|"
                    + "password=|secret=|token=|private[_-]?key|client[_-]?secret|"
                    + "\\.com|\\.net|\\.org|\\.io|\\.local|::ffff:|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");

    /** Name field denylist — full-path / command-line shapes that MUST
     *  NOT appear in the name (these are signals the agent leaked raw
     *  executable info; reject hard). */
    private static final Pattern NAME_FULLPATH_DENYLIST_RE = Pattern.compile(
            "(?i)("
                    + "[a-z]:\\\\"                  // Windows drive prefix "C:\"
                    + "|\\\\\\\\"                  // UNC prefix "\\"
                    + "|/[a-z]+/[a-z]+"            // Unix path segments
                    + "|\\.(exe|dll|bat|cmd|ps1|vbs)\\b"   // executable extensions w/ word boundary
                    + ")"
    );

    private final ObjectMapper canonicalMapper;

    public StartupExposurePayloadPolicy() {
        ObjectMapper m = new ObjectMapper();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.canonicalMapper = m;
    }

    /**
     * Pre-persist sanitizer hook — matches the AG-039-be
     * {@code ServicesPayloadPolicy.sanitize(details)} contract.
     * Closes the type-confusion bypass class (non-Map startupExposure
     * value skips this policy → generic software policy misses forbidden
     * keys).
     */
    public Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Object inventoryNode = details.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            Object seNode = inventoryMap.get("startupExposure");
            validatePresentNode(seNode, "$.inventory.startupExposure");
        }
        Object topNode = details.get("startupExposure");
        validatePresentNode(topNode, "$.startupExposure");
        return details;
    }

    @SuppressWarnings("unchecked")
    private void validatePresentNode(Object node, String path) {
        if (node == null) {
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "AG-040 startupExposure block at " + path
                            + " must be a Map or absent (got " + node.getClass().getName() + ")");
        }
        projectAndHash((Map<String, Object>) map);
    }

    public Projection projectAndHash(Map<String, Object> startupExposureBlock) {
        if (startupExposureBlock == null) {
            throw new IllegalArgumentException("AG-040 startupExposure block is null.");
        }
        // 1. Forbidden top-level keys (defense-in-depth).
        for (String key : startupExposureBlock.keySet()) {
            if (FORBIDDEN_TOP_KEYS.contains(key)) {
                log.warn("AG-040 startupExposure payload rejected: forbidden key '{}' present.", key);
                throw new IllegalArgumentException(
                        "AG-040 startupExposure payload contains forbidden key: " + key);
            }
        }
        // 2. Strict-allowlist top-level keys.
        for (String key : startupExposureBlock.keySet()) {
            if (!TOP_ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "AG-040 startupExposure payload contains unknown top-level key: " + key);
            }
        }
        // 3. Required keys.
        for (String required : TOP_REQUIRED_KEYS) {
            if (!startupExposureBlock.containsKey(required)) {
                throw new IllegalArgumentException(
                        "AG-040 startupExposure payload missing required key: " + required);
            }
        }

        // 4. Scalar parsing.
        int schemaVersion = readInt(startupExposureBlock, "schemaVersion");
        if (!ACCEPTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
            throw new IllegalArgumentException(
                    "AG-040 startupExposure unsupported schemaVersion: " + schemaVersion);
        }
        boolean supported = readBool(startupExposureBlock, "supported");
        boolean probeComplete = readBool(startupExposureBlock, "probeComplete");
        boolean rdpEnabled = readBool(startupExposureBlock, "rdpEnabled");
        boolean wfEvtLog = readBool(startupExposureBlock, "windowsFirewallEventLogEnabled");
        int probeDurationMs = readInt(startupExposureBlock, "probeDurationMs");
        if (probeDurationMs < 0 || probeDurationMs > PROBE_DURATION_MAX_MS) {
            throw new IllegalArgumentException(
                    "AG-040 startupExposure probeDurationMs out of range [0.." + PROBE_DURATION_MAX_MS
                            + "]: " + probeDurationMs);
        }

        // 5. StartupApps list validation.
        List<AppProjection> apps = projectStartupApps(
                startupExposureBlock.get("startupApps"));

        // 6. Optional probeErrors (absent ACCEPT, explicit null REJECT, non-List REJECT).
        List<ProbeErrorProjection> probeErrors = projectProbeErrors(
                startupExposureBlock.containsKey("probeErrors")
                        ? startupExposureBlock.get("probeErrors") : null,
                startupExposureBlock.containsKey("probeErrors"));

        // 7. Canonical hash projection (deterministic key order).
        Map<String, Object> hashMap = new LinkedHashMap<>();
        hashMap.put("schemaVersion", schemaVersion);
        hashMap.put("supported", supported);
        hashMap.put("probeComplete", probeComplete);
        hashMap.put("rdpEnabled", rdpEnabled);
        hashMap.put("windowsFirewallEventLogEnabled", wfEvtLog);
        hashMap.put("probeDurationMs", probeDurationMs);
        List<Map<String, Object>> appList = new ArrayList<>(apps.size());
        for (AppProjection a : apps) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("name", a.name());
            am.put("location", a.location());
            am.put("enabled", a.enabled());
            am.put("probeOrigin", a.probeOrigin());
            appList.add(am);
        }
        hashMap.put("startupApps", appList);
        List<Map<String, Object>> peList = new ArrayList<>(probeErrors.size());
        for (ProbeErrorProjection pe : probeErrors) {
            Map<String, Object> peMap = new LinkedHashMap<>();
            peMap.put("code", pe.code());
            peMap.put("source", pe.source());
            peMap.put("summary", pe.summary());
            peList.add(peMap);
        }
        hashMap.put("probeErrors", peList);

        String hashHex = sha256Hex(hashMap);

        return new Projection(
                schemaVersion, supported, probeComplete,
                rdpEnabled, wfEvtLog, probeDurationMs,
                apps, probeErrors, hashHex);
    }

    @SuppressWarnings("unchecked")
    private List<AppProjection> projectStartupApps(Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("AG-040 startupApps is null (must be array).");
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException(
                    "AG-040 startupApps must be a List (got " + raw.getClass().getName() + ")");
        }
        if (rawList.size() > STARTUP_APPS_MAX) {
            throw new IllegalArgumentException(
                    "AG-040 startupApps exceeds cap of " + STARTUP_APPS_MAX);
        }
        List<AppProjection> out = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException(
                        "AG-040 startupApps[" + i + "] must be a Map (got "
                                + (item == null ? "null" : item.getClass().getName()) + ")");
            }
            for (Object k : itemMap.keySet()) {
                if (!(k instanceof String keyStr) || !APP_ALLOWED_KEYS.contains(keyStr)) {
                    throw new IllegalArgumentException(
                            "AG-040 startupApps[" + i + "] contains unknown key: " + k);
                }
            }
            for (String required : APP_ALLOWED_KEYS) {
                if (!itemMap.containsKey(required)) {
                    throw new IllegalArgumentException(
                            "AG-040 startupApps[" + i + "] missing required key: " + required);
                }
            }
            String name = asString(itemMap.get("name"), "startupApps[" + i + "].name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException(
                        "AG-040 startupApps[" + i + "].name must be non-empty");
            }
            if (name.length() > NAME_MAX_LEN) {
                throw new IllegalArgumentException(
                        "AG-040 startupApps[" + i + "].name exceeds length cap " + NAME_MAX_LEN);
            }
            if (CONTROL_CHAR_RE.matcher(name).find()) {
                throw new IllegalArgumentException(
                        "AG-040 startupApps[" + i + "].name contains control character");
            }
            if (NAME_FULLPATH_DENYLIST_RE.matcher(name).find()) {
                throw new IllegalArgumentException(
                        "AG-040 startupApps[" + i + "].name contains forbidden value pattern "
                                + "(drive letter / UNC / unix path / executable extension)");
            }
            String location = asString(itemMap.get("location"), "startupApps[" + i + "].location");
            if (!CANONICAL_LOCATIONS_SET.contains(location)) {
                throw new IllegalArgumentException(
                        "AG-040 startupApps[" + i + "].location must be in canonical anchor allowlist; got "
                                + location);
            }
            boolean enabled = asBool(itemMap.get("enabled"), "startupApps[" + i + "].enabled");
            String probeOrigin = asString(itemMap.get("probeOrigin"), "startupApps[" + i + "].probeOrigin");
            if (!PROBE_ORIGIN_ENUM.contains(probeOrigin)) {
                throw new IllegalArgumentException(
                        "AG-040 startupApps[" + i + "].probeOrigin must be in " + PROBE_ORIGIN_ENUM
                                + " got " + probeOrigin);
            }
            out.add(new AppProjection(i, name, location, enabled, probeOrigin));
        }
        return out;
    }

    private List<ProbeErrorProjection> projectProbeErrors(Object raw, boolean explicitlyPresent) {
        if (!explicitlyPresent) {
            return List.of();
        }
        if (raw == null) {
            throw new IllegalArgumentException(
                    "AG-040 startupExposure probeErrors must be a List or omitted, not null");
        }
        if (!(raw instanceof List<?> rawList)) {
            throw new IllegalArgumentException(
                    "AG-040 startupExposure probeErrors must be a List (got "
                            + raw.getClass().getName() + ")");
        }
        if (rawList.size() > PROBE_ERRORS_MAX) {
            throw new IllegalArgumentException(
                    "AG-040 startupExposure probeErrors exceeds cap of " + PROBE_ERRORS_MAX);
        }
        List<ProbeErrorProjection> out = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            if (!(item instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException(
                        "AG-040 startupExposure probeErrors[" + i + "] must be a Map.");
            }
            for (Object k : itemMap.keySet()) {
                if (!(k instanceof String keyStr) || !PROBE_ERROR_ALLOWED_KEYS.contains(keyStr)) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "] contains unknown key: " + k);
                }
            }
            if (!itemMap.containsKey("code")) {
                throw new IllegalArgumentException(
                        "AG-040 startupExposure probeErrors[" + i + "] missing required key: code");
            }
            String code = asString(itemMap.get("code"), "probeErrors[" + i + "].code");
            if (!PROBE_ERROR_CODE_ENUM.contains(code)) {
                throw new IllegalArgumentException(
                        "AG-040 startupExposure probeErrors[" + i + "].code must be in "
                                + PROBE_ERROR_CODE_ENUM + " got " + code);
            }
            String source = null;
            if (itemMap.containsKey("source")) {
                Object rawSrc = itemMap.get("source");
                if (rawSrc == null) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].source must be omitted, not null");
                }
                if (!(rawSrc instanceof String srcStr)) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].source must be a String.");
                }
                if (srcStr.isEmpty()) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].source must be non-empty (omit the key instead)");
                }
                if (!CANONICAL_LOCATIONS_SET.contains(srcStr)) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].source must be in canonical anchor allowlist; got " + srcStr);
                }
                source = srcStr;
            }
            String summary = null;
            if (itemMap.containsKey("summary")) {
                Object rawSummary = itemMap.get("summary");
                if (rawSummary == null) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].summary must be omitted, not null");
                }
                if (!(rawSummary instanceof String s)) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].summary must be a String.");
                }
                if (s.isEmpty()) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].summary must be non-empty (omit the key instead)");
                }
                if (s.length() > SUMMARY_MAX_LEN) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].summary exceeds length cap");
                }
                if (CONTROL_CHAR_RE.matcher(s).find()) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].summary contains control character");
                }
                if (SUMMARY_VALUE_DENYLIST_RE.matcher(s).find()) {
                    throw new IllegalArgumentException(
                            "AG-040 startupExposure probeErrors[" + i + "].summary contains forbidden value pattern (URL/token/host/IP)");
                }
                summary = s;
            }
            out.add(new ProbeErrorProjection(i, code, source, summary));
        }
        return out;
    }

    private int readInt(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("AG-040 startupExposure " + key + " is null.");
        }
        if (raw instanceof Integer i) return i;
        if (raw instanceof Long l) return Math.toIntExact(l);
        if (raw instanceof Short s) return s.intValue();
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            int iv = n.intValue();
            if (d != iv) {
                throw new IllegalArgumentException(
                        "AG-040 startupExposure " + key + " must be an integer (got: " + raw + ")");
            }
            return iv;
        }
        throw new IllegalArgumentException(
                "AG-040 startupExposure " + key + " must be an integer (got: " + raw.getClass() + ")");
    }

    private boolean readBool(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("AG-040 startupExposure " + key + " is null.");
        }
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException(
                "AG-040 startupExposure " + key + " must be a Boolean (got: " + raw.getClass() + ")");
    }

    private String asString(Object raw, String label) {
        if (raw == null) {
            throw new IllegalArgumentException("AG-040 startupExposure " + label + " is null.");
        }
        if (raw instanceof String s) return s;
        throw new IllegalArgumentException(
                "AG-040 startupExposure " + label + " must be a String (got: " + raw.getClass() + ")");
    }

    private boolean asBool(Object raw, String label) {
        if (raw == null) {
            throw new IllegalArgumentException("AG-040 startupExposure " + label + " is null.");
        }
        if (raw instanceof Boolean b) return b;
        throw new IllegalArgumentException(
                "AG-040 startupExposure " + label + " must be a Boolean (got: " + raw.getClass() + ")");
    }

    private String sha256Hex(Map<String, Object> canonical) {
        try {
            String json = canonicalMapper.writeValueAsString(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "AG-040 startupExposure canonical-form JSON serialization failed.", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable.", ex);
        }
    }

    public record Projection(
            int schemaVersion,
            boolean supported,
            boolean probeComplete,
            boolean rdpEnabled,
            boolean windowsFirewallEventLogEnabled,
            int probeDurationMs,
            List<AppProjection> startupApps,
            List<ProbeErrorProjection> probeErrors,
            String payloadHashSha256) {
    }

    public record AppProjection(
            int rowOrdinal,
            String name,
            String location,
            boolean enabled,
            String probeOrigin) {
    }

    public record ProbeErrorProjection(
            int rowOrdinal,
            String code,
            String source,
            String summary) {
    }
}
