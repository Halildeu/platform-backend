package com.example.endpointadmin.security;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BE — pre-persist sanitizer/validator for the device-health sub-tree of
 * the agent {@code COLLECT_INVENTORY} payload (Faz 22.5, AG-033 ingest).
 * Mirrors the BE-022 {@link HardwareInventoryPayloadPolicy} pattern, but
 * enforces the device-health contract's <em>tighter</em> redaction
 * boundary (schema/endpoint-device-health-payload-v1.schema.json, gitops
 * PR #1143 commit {@code ddd5e326}).
 *
 * <p>Wire path: the block is carried at
 * {@code details.inventory.deviceHealth} (nullable — absent when the
 * caller did not opt in; AG-025H lightweight default). When present it
 * MUST conform to the schema.
 *
 * <p>Redaction boundary (security invariant — DO NOT widen):
 * <table>
 *   <tr><th>Field group</th><th>On the wire</th><th>NEVER on the wire</th></tr>
 *   <tr><td>Disk</td><td>{@code driveLetter} ({@code ^[A-Z]:$}), byte
 *       totals, derived percent, warning</td><td>volume label, serial,
 *       filesystem, mount path, GUID</td></tr>
 *   <tr><td>Memory</td><td>byte totals, used %, commit summary</td>
 *       <td>per-process accounting</td></tr>
 *   <tr><td>Uptime</td><td>{@code lastBootEpochSec} (unix seconds),
 *       seconds/days, warning</td><td>local-time string, timezone,
 *       locale</td></tr>
 *   <tr><td>Errors</td><td>{@code code} (enum), bounded {@code summary}
 *       (&le;200)</td><td>raw errno, filesystem path</td></tr>
 * </table>
 *
 * <p>Unlike the hardware policy (which STRIPS sensitive disk identifiers
 * to {@code <redacted>}), the device-health disk facet has a strict
 * allowlist: a disk object carrying ANY key outside
 * {@code {driveLetter, totalBytes, freeBytes, freePercent, lowDiskWarning}}
 * is <strong>fail-closed rejected</strong> (out-of-shape), because the
 * contract's {@code fixedDiskHealth} is {@code additionalProperties:
 * false} — a label / serial / filesystem / mount / GUID key has no
 * legitimate place there and its presence means an off-contract agent or
 * an intermediary is shipping something it shouldn't.
 *
 * <p>Forward-compat (contract §"Forward-compat rule"): the policy is
 * runtime-tolerant of unknown <em>top-level</em> device-health fields
 * (ignored), but strict on the disk facet shape and on the known v1
 * fields it validates. A genuinely new shape bumps {@code schemaVersion}.
 *
 * <p>Schema validation: {@link #sanitize(Map)} validates the schema
 * version ({@code const 1}), the {@code sourceUsed} enum
 * ({@code win32 | none}), numeric ranges, the disk-facet allowlist +
 * {@code driveLetter} pattern, and fail-closed rejects secret value
 * patterns (bearer / JWT / kv leaks) and out-of-shape sub-trees with
 * {@link IllegalArgumentException}.
 */
@Component
public class DeviceHealthPayloadPolicy {

    /**
     * Keys whose value, anywhere in the device-health sub-tree, must
     * cause a fail-closed reject. Secrets have no legitimate
     * device-health presence.
     */
    private static final Set<String> REJECT_KEY_LOWER = Set.of(
            "token",
            "bearer",
            "jwt",
            "password",
            "secret"
    );

    /**
     * Allowlist of keys on a fixed-disk facet object. The contract's
     * {@code fixedDiskHealth} is {@code additionalProperties: false}
     * with exactly these five required keys. Any other key (label,
     * serial, filesystem, mountPath, guid, volumeName, ...) is
     * fail-closed rejected — that is precisely the redaction boundary
     * the agent enforces at source, mirrored here machine-side.
     */
    private static final Set<String> DISK_ALLOWED_KEYS_LOWER = Set.of(
            "driveletter",
            "totalbytes",
            "freebytes",
            "freepercent",
            "lowdiskwarning"
    );

    /** Drive-letter shape — the ONLY disk identifier on the wire. */
    private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Z]:$");

    /** Accepted {@code schemaVersion} values (current agent emits 1). */
    private static final Set<Integer> ACCEPTED_SCHEMA_VERSIONS = Set.of(1);

    /** Accepted {@code sourceUsed} enum values. */
    private static final Set<String> ACCEPTED_SOURCE_USED =
            Set.of("win32", "none");

    /** Value pattern matchers — applied to every string scalar,
     * regardless of key name (defense-in-depth on probeError summaries,
     * which the contract says must never be a path / errno). */
    private static final Pattern USERS_PATH = Pattern.compile(
            "(?i)c:\\\\users\\\\[^\\\\]+");

    private static final Pattern UNIX_USER_PATH = Pattern.compile(
            "/(home|Users)/[^/]+");

    private static final Pattern WINDOWS_SID = Pattern.compile(
            "S-1-5-21-\\d+-\\d+-\\d+-\\d+");

    /** Braced machine GUID — `{XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}`. */
    private static final Pattern MACHINE_GUID = Pattern.compile(
            "(?i)\\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\}");

    /**
     * Value-level secret pattern denylist (parity with the hardware
     * policy must-fix #3). Key-level reject covers
     * {@code token / password / jwt / bearer / secret}, but raw
     * agent-reported strings (e.g. a {@code probeError.summary}) can
     * also leak secrets out of band. These patterns fail-closed reject
     * the entire result so the snapshot, the command result, and any
     * audit copy of the payload all roll back together.
     */
    private static final Pattern[] SECRET_VALUE_PATTERNS = new Pattern[]{
            Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._=+/-]{10,}"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
            Pattern.compile("(?i)\\b(password|secret|api[-_]?key|access[-_]?token|refresh[-_]?token)\\s*[=:]\\s*[^\\s]{4,}")
    };

    public static final String REDACTED = "<redacted>";

    /**
     * Walk {@code details} producing a new map with the device-health
     * sub-tree validated + sanitized. The original map is not modified.
     *
     * @param details the agent {@code result_payload.details} map
     * @return sanitized {@code effectiveDetails} — caller hands this to
     *         the persisted {@code result_payload} and to the
     *         device-health ingest hook. {@code null} when {@code details}
     *         is {@code null}.
     */
    public Map<String, Object> sanitize(Map<String, Object> details) {
        if (details == null) {
            return null;
        }
        Map<String, Object> sanitized = deepCopyMap(details);
        Object inventoryNode = sanitized.get("inventory");
        if (inventoryNode instanceof Map<?, ?> inventoryMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) inventoryMap;
            Object deviceHealthNode = typed.get("deviceHealth");
            if (deviceHealthNode instanceof Map<?, ?> dhMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dhTyped = (Map<String, Object>) dhMap;
                typed.put("deviceHealth",
                        sanitizeDeviceHealth(dhTyped, "$.inventory.deviceHealth"));
            }
        }
        // Some agent versions may also place the block at the top level —
        // sanitize that too if present (parity with hardware policy).
        Object topNode = sanitized.get("deviceHealth");
        if (topNode instanceof Map<?, ?> topMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dhTyped = (Map<String, Object>) topMap;
            sanitized.put("deviceHealth",
                    sanitizeDeviceHealth(dhTyped, "$.deviceHealth"));
        }
        return sanitized;
    }

    /**
     * Validate + sanitize a device-health sub-tree map. Performs:
     * <ul>
     *   <li>Schema version check ({@code const 1})</li>
     *   <li>{@code sourceUsed} enum check ({@code win32 | none})</li>
     *   <li>Numeric range checks (counts, durations, percents)</li>
     *   <li>{@code fixedDisks} array shape + per-disk allowlist +
     *       {@code driveLetter} pattern</li>
     *   <li>{@code memory} / {@code uptime} object shape</li>
     *   <li>Key-based reject (secrets) + value-pattern reject/strip</li>
     * </ul>
     */
    private Map<String, Object> sanitizeDeviceHealth(Map<String, Object> dh, String path) {
        // Schema version.
        Object schemaVersionObj = dh.get("schemaVersion");
        if (schemaVersionObj != null) {
            Integer schemaVersion = toInt(schemaVersionObj, path + ".schemaVersion");
            if (schemaVersion == null || !ACCEPTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
                throw new IllegalArgumentException(
                        "Unsupported device-health schema_version=" + schemaVersionObj
                                + " at " + path + ".schemaVersion");
            }
        }

        // sourceUsed enum.
        Object sourceUsed = dh.get("sourceUsed");
        if (sourceUsed != null) {
            String su = String.valueOf(sourceUsed);
            if (!ACCEPTED_SOURCE_USED.contains(su)) {
                throw new IllegalArgumentException(
                        "Unsupported device-health sourceUsed='" + su
                                + "' at " + path + ".sourceUsed (expected win32 | none)");
            }
        }

        // Top-level numeric ranges.
        requireNonNegative(dh.get("fixedDiskCount"), path + ".fixedDiskCount");
        requireNonNegative(dh.get("maxFixedDisks"), path + ".maxFixedDisks");
        requireNonNegative(dh.get("probeDurationMs"), path + ".probeDurationMs");

        // fixedDisks must be an array (or absent) — and each entry must
        // conform to the strict disk allowlist + driveLetter pattern.
        Object fixedDisksRaw = dh.get("fixedDisks");
        ensureArrayOrAbsent(fixedDisksRaw, path + ".fixedDisks");
        if (fixedDisksRaw instanceof List<?> fixedDisks) {
            int idx = 0;
            for (Object diskObj : fixedDisks) {
                String diskPath = path + ".fixedDisks[" + idx + "]";
                if (!(diskObj instanceof Map<?, ?> diskMap)) {
                    throw new IllegalArgumentException(
                            "Expected object at " + diskPath + " but got "
                                    + (diskObj == null ? "null"
                                            : diskObj.getClass().getSimpleName()));
                }
                validateDiskFacet(diskMap, diskPath);
                idx++;
            }
        }

        // memory / uptime object range checks (shape-tolerant: extra
        // fields ignored, known fields validated).
        Object memory = dh.get("memory");
        if (memory instanceof Map<?, ?> memMap) {
            requireNonNegative(memMap.get("totalPhysicalBytes"),
                    path + ".memory.totalPhysicalBytes");
            requireNonNegative(memMap.get("availableBytes"),
                    path + ".memory.availableBytes");
            requirePercent(memMap.get("usedPercent"), path + ".memory.usedPercent");
            requireNonNegative(memMap.get("commitLimitBytes"),
                    path + ".memory.commitLimitBytes");
            requireNonNegative(memMap.get("commitUsedBytes"),
                    path + ".memory.commitUsedBytes");
        }

        Object uptime = dh.get("uptime");
        if (uptime instanceof Map<?, ?> upMap) {
            requireNonNegative(upMap.get("lastBootEpochSec"),
                    path + ".uptime.lastBootEpochSec");
            requireNonNegative(upMap.get("uptimeSeconds"),
                    path + ".uptime.uptimeSeconds");
            requireNonNegative(upMap.get("uptimeDays"),
                    path + ".uptime.uptimeDays");
        }

        // Walk + sanitize recursively (key reject + value reject/strip).
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) walkSanitize(dh, path);
        return out;
    }

    /**
     * Validate a single fixed-disk facet against the strict contract
     * shape: only the five allowed keys, and {@code driveLetter} must
     * match {@code ^[A-Z]:$}. Any other key is fail-closed rejected.
     */
    private void validateDiskFacet(Map<?, ?> diskMap, String diskPath) {
        for (Map.Entry<?, ?> entry : diskMap.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String keyLower = key.toLowerCase(Locale.ROOT);
            if (!DISK_ALLOWED_KEYS_LOWER.contains(keyLower)) {
                throw new IllegalArgumentException(
                        "Forbidden device-health disk key '" + key + "' at "
                                + diskPath + "." + key
                                + " — the disk facet redaction boundary allows ONLY"
                                + " {driveLetter, totalBytes, freeBytes, freePercent,"
                                + " lowDiskWarning} (no label / serial / filesystem /"
                                + " mount path / GUID).");
            }
        }
        Object driveLetter = diskMap.get("driveLetter");
        if (driveLetter == null) {
            throw new IllegalArgumentException(
                    "Missing driveLetter at " + diskPath + ".driveLetter");
        }
        String dl = String.valueOf(driveLetter);
        if (!DRIVE_LETTER.matcher(dl).matches()) {
            throw new IllegalArgumentException(
                    "Invalid driveLetter '" + dl + "' at " + diskPath
                            + ".driveLetter (expected ^[A-Z]:$)");
        }
        requireNonNegative(diskMap.get("totalBytes"), diskPath + ".totalBytes");
        requireNonNegative(diskMap.get("freeBytes"), diskPath + ".freeBytes");
        requirePercent(diskMap.get("freePercent"), diskPath + ".freePercent");
    }

    @SuppressWarnings("unchecked")
    private Object walkSanitize(Object node, String path) {
        if (node == null) {
            return null;
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String keyLower = key.toLowerCase(Locale.ROOT);
                String childPath = path + "." + key;

                if (REJECT_KEY_LOWER.contains(keyLower)) {
                    throw new IllegalArgumentException(
                            "Forbidden device-health key '" + key + "' at "
                                    + childPath
                                    + " — secrets must not appear in device-health payloads.");
                }
                out.put(key, walkSanitize(entry.getValue(), childPath));
            }
            return out;
        }
        if (node instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object element : iterable) {
                out.add(walkSanitize(element, path + "[" + (i++) + "]"));
            }
            return out;
        }
        if (node instanceof String s) {
            return redactStringValue(s, path);
        }
        return node;
    }

    /**
     * Apply value-level redaction to a string scalar (parity with the
     * hardware policy's two-tier behavior):
     * <ol>
     *   <li>Secret value patterns (Bearer / JWT / kv leaks) fail-closed
     *       throw — strip is unsafe because the surrounding structure may
     *       still leak the secret length or position.</li>
     *   <li>Identifier patterns (user paths, SIDs, machine GUIDs) are
     *       replaced with {@value #REDACTED} — the contract says a
     *       probeError summary must never be a path; if an off-contract
     *       agent embeds one, we neutralize it rather than persist it.</li>
     * </ol>
     */
    private String redactStringValue(String value, String path) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        for (Pattern pattern : SECRET_VALUE_PATTERNS) {
            if (pattern.matcher(value).find()) {
                throw new IllegalArgumentException(
                        "Secret value pattern detected at " + path
                                + " — tokens / passwords / bearer headers must not appear"
                                + " in device-health payloads.");
            }
        }
        String out = value;
        out = USERS_PATH.matcher(out).replaceAll(REDACTED);
        out = UNIX_USER_PATH.matcher(out).replaceAll(REDACTED);
        out = WINDOWS_SID.matcher(out).replaceAll(REDACTED);
        out = MACHINE_GUID.matcher(out).replaceAll(REDACTED);
        return out;
    }

    private static void requireNonNegative(Object value, String path) {
        if (value == null) {
            return;
        }
        Number n = toNumber(value, path);
        if (n != null && n.longValue() < 0) {
            throw new IllegalArgumentException(
                    "Negative value not allowed at " + path + ": " + value);
        }
    }

    private static void requirePercent(Object value, String path) {
        if (value == null) {
            return;
        }
        Number n = toNumber(value, path);
        if (n == null) {
            return;
        }
        long v = n.longValue();
        if (v < 0 || v > 100) {
            throw new IllegalArgumentException(
                    "Percent out of range [0,100] at " + path + ": " + value);
        }
    }

    private static void ensureArrayOrAbsent(Object value, String path) {
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(
                    "Expected array at " + path + " but got " + value.getClass().getSimpleName());
        }
    }

    private static Integer toInt(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "Expected integer at " + path + " but got '" + s + "'");
            }
        }
        throw new IllegalArgumentException(
                "Expected integer at " + path + " but got " + value.getClass().getSimpleName());
    }

    private static Number toNumber(Object value, String path) {
        if (value instanceof Number n) {
            return n;
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ex) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException ex2) {
                    throw new IllegalArgumentException(
                            "Expected number at " + path + " but got '" + s + "'");
                }
            }
        }
        throw new IllegalArgumentException(
                "Expected number at " + path + " but got " + value.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : in.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> m) {
                out.put(entry.getKey(), deepCopyMap((Map<String, Object>) m));
            } else if (value instanceof List<?> list) {
                out.put(entry.getKey(), deepCopyList(list));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> deepCopyList(List<?> in) {
        List<Object> out = new ArrayList<>(in.size());
        for (Object element : in) {
            if (element instanceof Map<?, ?> m) {
                out.add(deepCopyMap((Map<String, Object>) m));
            } else if (element instanceof List<?> nested) {
                out.add(deepCopyList(nested));
            } else {
                out.add(element);
            }
        }
        return out;
    }
}
