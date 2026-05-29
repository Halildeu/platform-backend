package com.example.endpointadmin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><strong>Allowlist projection (Codex 019e… P1-1 must-fix).</strong>
 * The output of {@link #sanitize(Map)} is the SAME object that flows into
 * BOTH the persisted command-result {@code result_payload.details} and the
 * device-health snapshot {@code redacted_payload}. The policy therefore
 * does not merely walk-and-copy the agent map (which would carry
 * off-contract keys such as {@code volumeLabel}, {@code processList},
 * {@code localBootTime}, {@code rawOutput} verbatim into both sinks). It
 * builds a <em>canonical projection</em> containing only the v1 contract
 * keys; any unknown key — top-level, nested in {@code memory}/{@code uptime},
 * or on a {@code probeErrors[]} element — is DROPPED (and debug-logged).
 * This is exactly the contract's forward-compat rule
 * (docs/faz-22-device-health-contract-v1.md §"Forward-compat rule":
 * unknown top-level fields are <em>ignored, not persisted</em>). The
 * stricter disk facet ({@code additionalProperties: false}) is fail-closed
 * <em>rejected</em> (not dropped) on any extra key, because a label /
 * serial / filesystem / mount / GUID has no legitimate place there.
 *
 * <p><strong>Fail-closed required fields (Codex 019e… P1-2 must-fix).</strong>
 * The policy validates required v1 fields like a JSON-schema validator:
 * a missing/wrong-typed required top-level field, a missing/wrong-typed
 * required {@code memory}/{@code uptime} subfield, or a non-enum
 * {@code sourceUsed} is fail-closed <em>rejected</em> with
 * {@link IllegalArgumentException}. A minimal {@code {"schemaVersion":1}}
 * block is rejected — the downstream service must NEVER synthesize
 * "healthy" defaults for required fields.
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
 */
@Component
public class DeviceHealthPayloadPolicy {

    private static final Logger log = LoggerFactory.getLogger(DeviceHealthPayloadPolicy.class);

    /**
     * Bounded probeError {@code summary} cap. The contract pins
     * {@code maxLength: 200}; the policy enforces it on the projection so
     * the command-result payload, the snapshot {@code redacted_payload},
     * and any audit copy are all bounded — not just the persisted entity
     * scalar (Codex 019e… P1-3 must-fix). Exposed so the snapshot service
     * shares the SAME constant instead of an independent 256 value.
     */
    public static final int SUMMARY_MAX_LEN = 200;

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
     * Allowlist of v1 top-level device-health keys (contract
     * {@code additionalProperties: false} + required set, plus the optional
     * {@code probeErrors}). Any other top-level key is DROPPED (forward-compat
     * "ignore unknown", never persisted).
     */
    private static final Set<String> TOP_ALLOWED_KEYS = Set.of(
            "schemaVersion",
            "supported",
            "probeComplete",
            "fixedDisks",
            "fixedDiskCount",
            "fixedDisksTruncated",
            "maxFixedDisks",
            "memory",
            "uptime",
            "anyLowDisk",
            "sourceUsed",
            "probeErrors",
            "probeDurationMs"
    );

    /** Required top-level fields (contract {@code required}). */
    private static final Set<String> TOP_REQUIRED_KEYS = Set.of(
            "schemaVersion",
            "supported",
            "probeComplete",
            "fixedDisks",
            "fixedDiskCount",
            "fixedDisksTruncated",
            "maxFixedDisks",
            "memory",
            "uptime",
            "anyLowDisk",
            "sourceUsed",
            "probeDurationMs"
    );

    /** Allowlist of {@code memory} keys (contract {@code memoryHealth}). */
    private static final Set<String> MEMORY_ALLOWED_KEYS = Set.of(
            "totalPhysicalBytes",
            "availableBytes",
            "usedPercent",
            "highPressureWarning",
            "commitLimitBytes",
            "commitUsedBytes"
    );

    /** Allowlist of {@code uptime} keys (contract {@code uptimeHealth}). */
    private static final Set<String> UPTIME_ALLOWED_KEYS = Set.of(
            "lastBootEpochSec",
            "uptimeSeconds",
            "uptimeDays",
            "longUptimeWarning"
    );

    /** Allowlist of {@code probeErrors[]} element keys (contract
     *  {@code probeError}; {@code code} required, {@code source}/{@code summary}
     *  optional). */
    private static final Set<String> PROBE_ERROR_ALLOWED_KEYS = Set.of(
            "source",
            "code",
            "summary"
    );

    /** Accepted {@code probeError.code} enum (contract). */
    private static final Set<String> PROBE_ERROR_CODES = Set.of(
            "UNSUPPORTED_PLATFORM",
            "DISK_ENUM_FAILED",
            "MEMORY_QUERY_FAILED",
            "UPTIME_QUERY_FAILED",
            "BOOT_TIME_FAILED",
            "NO_EVIDENCE"
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
     * sub-tree validated + projected onto the canonical v1 shape. The
     * original map is not modified.
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
     * Validate + project a device-health sub-tree map onto the canonical
     * v1 shape. Performs (in order):
     * <ul>
     *   <li>Secret key/value reject over the RAW sub-tree (so a secret in
     *       an off-contract key is rejected before that key is dropped)</li>
     *   <li>Required top-level field presence + type (fail-closed)</li>
     *   <li>Schema version ({@code const 1}) + {@code sourceUsed} enum</li>
     *   <li>Numeric range checks (counts, durations, percents)</li>
     *   <li>{@code fixedDisks} array shape + per-disk allowlist (reject) +
     *       {@code driveLetter} pattern</li>
     *   <li>{@code memory} / {@code uptime} required-subfield presence +
     *       range</li>
     *   <li>{@code probeErrors[]} shape ({@code code} enum, bounded summary)</li>
     *   <li>Allowlist projection — only canonical v1 keys survive; unknown
     *       top-level / memory / uptime / probeError keys are dropped +
     *       debug-logged</li>
     * </ul>
     */
    private Map<String, Object> sanitizeDeviceHealth(Map<String, Object> dh, String path) {
        // 0. Secret reject over the RAW sub-tree FIRST. A secret hidden in
        //    an off-contract key (which the projection below would drop)
        //    must still fail-closed the whole result, not be silently
        //    discarded. Also applies value-pattern reject to every scalar.
        rejectSecrets(dh, path);

        // 1. Required top-level fields (fail-closed). A minimal
        //    {"schemaVersion":1} block is rejected here.
        for (String req : TOP_REQUIRED_KEYS) {
            if (!dh.containsKey(req) || dh.get(req) == null) {
                throw new IllegalArgumentException(
                        "Missing required device-health field '" + req + "' at "
                                + path + "." + req
                                + " — required v1 fields are fail-closed (no healthy default).");
            }
        }

        // 2. Schema version (const 1).
        Integer schemaVersion = toInt(dh.get("schemaVersion"), path + ".schemaVersion");
        if (schemaVersion == null || !ACCEPTED_SCHEMA_VERSIONS.contains(schemaVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported device-health schema_version=" + dh.get("schemaVersion")
                            + " at " + path + ".schemaVersion");
        }

        // 3. Boolean-typed required fields.
        requireBoolean(dh.get("supported"), path + ".supported");
        requireBoolean(dh.get("probeComplete"), path + ".probeComplete");
        requireBoolean(dh.get("fixedDisksTruncated"), path + ".fixedDisksTruncated");
        requireBoolean(dh.get("anyLowDisk"), path + ".anyLowDisk");

        // 4. sourceUsed enum (required + must be win32|none).
        String su = String.valueOf(dh.get("sourceUsed"));
        if (!ACCEPTED_SOURCE_USED.contains(su)) {
            throw new IllegalArgumentException(
                    "Unsupported device-health sourceUsed='" + su
                            + "' at " + path + ".sourceUsed (expected win32 | none)");
        }

        // 5. Top-level numeric ranges (required → non-null already proven).
        requireNonNegative(dh.get("fixedDiskCount"), path + ".fixedDiskCount");
        requireNonNegative(dh.get("maxFixedDisks"), path + ".maxFixedDisks");
        requireNonNegative(dh.get("probeDurationMs"), path + ".probeDurationMs");

        // 6. fixedDisks must be an array — and each entry must conform to
        //    the strict disk allowlist + driveLetter pattern.
        Object fixedDisksRaw = dh.get("fixedDisks");
        if (!(fixedDisksRaw instanceof List<?>)) {
            throw new IllegalArgumentException(
                    "Expected array at " + path + ".fixedDisks but got "
                            + fixedDisksRaw.getClass().getSimpleName());
        }
        List<Object> projectedDisks = new ArrayList<>();
        int idx = 0;
        for (Object diskObj : (List<?>) fixedDisksRaw) {
            String diskPath = path + ".fixedDisks[" + idx + "]";
            if (!(diskObj instanceof Map<?, ?> diskMap)) {
                throw new IllegalArgumentException(
                        "Expected object at " + diskPath + " but got "
                                + (diskObj == null ? "null"
                                        : diskObj.getClass().getSimpleName()));
            }
            projectedDisks.add(projectDiskFacet(diskMap, diskPath));
            idx++;
        }

        // 7. memory — required object with required subfields.
        Object memoryRaw = dh.get("memory");
        if (!(memoryRaw instanceof Map<?, ?> memMap)) {
            throw new IllegalArgumentException(
                    "Expected object at " + path + ".memory but got "
                            + memoryRaw.getClass().getSimpleName());
        }
        Map<String, Object> projectedMemory =
                projectMemory(memMap, path + ".memory");

        // 8. uptime — required object with required subfields.
        Object uptimeRaw = dh.get("uptime");
        if (!(uptimeRaw instanceof Map<?, ?> upMap)) {
            throw new IllegalArgumentException(
                    "Expected object at " + path + ".uptime but got "
                            + uptimeRaw.getClass().getSimpleName());
        }
        Map<String, Object> projectedUptime =
                projectUptime(upMap, path + ".uptime");

        // 9. probeErrors — optional array of bounded {source,code,summary}.
        Object probeErrorsRaw = dh.get("probeErrors");
        List<Object> projectedProbeErrors = null;
        if (probeErrorsRaw != null) {
            if (!(probeErrorsRaw instanceof List<?>)) {
                throw new IllegalArgumentException(
                        "Expected array at " + path + ".probeErrors but got "
                                + probeErrorsRaw.getClass().getSimpleName());
            }
            projectedProbeErrors = new ArrayList<>();
            int ei = 0;
            for (Object errObj : (List<?>) probeErrorsRaw) {
                String errPath = path + ".probeErrors[" + ei + "]";
                if (!(errObj instanceof Map<?, ?> errMap)) {
                    throw new IllegalArgumentException(
                            "Expected object at " + errPath + " but got "
                                    + (errObj == null ? "null"
                                            : errObj.getClass().getSimpleName()));
                }
                projectedProbeErrors.add(projectProbeError(errMap, errPath));
                ei++;
            }
        }

        // 10. Allowlist projection — assemble the canonical v1 map. Only
        //     contract keys survive; unknown top-level keys are dropped
        //     (debug-logged), never persisted.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", dh.get("schemaVersion"));
        out.put("supported", dh.get("supported"));
        out.put("probeComplete", dh.get("probeComplete"));
        out.put("fixedDisks", projectedDisks);
        out.put("fixedDiskCount", dh.get("fixedDiskCount"));
        out.put("fixedDisksTruncated", dh.get("fixedDisksTruncated"));
        out.put("maxFixedDisks", dh.get("maxFixedDisks"));
        out.put("memory", projectedMemory);
        out.put("uptime", projectedUptime);
        out.put("anyLowDisk", dh.get("anyLowDisk"));
        out.put("sourceUsed", su);
        if (projectedProbeErrors != null) {
            out.put("probeErrors", projectedProbeErrors);
        }
        out.put("probeDurationMs", dh.get("probeDurationMs"));

        logDroppedKeys(dh.keySet(), TOP_ALLOWED_KEYS, path);
        return out;
    }

    /**
     * Validate + project a single fixed-disk facet. The disk facet is
     * stricter than the rest of the tree: any key outside the five-key
     * allowlist is fail-closed REJECTED (not dropped) — a label / serial /
     * filesystem / mount / GUID has no legitimate place on the wire.
     */
    private Map<String, Object> projectDiskFacet(Map<?, ?> diskMap, String diskPath) {
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
        requireBoolean(diskMap.get("lowDiskWarning"), diskPath + ".lowDiskWarning");

        // Project the canonical five-key disk facet (all five required +
        // already validated). Disk facet has no extra keys (rejected
        // above), so this is a straight canonical re-emit.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("driveLetter", dl);
        out.put("totalBytes", diskMap.get("totalBytes"));
        out.put("freeBytes", diskMap.get("freeBytes"));
        out.put("freePercent", diskMap.get("freePercent"));
        out.put("lowDiskWarning", diskMap.get("lowDiskWarning"));
        return out;
    }

    /** Validate required {@code memory} subfields + project the canonical
     *  six-key object. Unknown memory keys (e.g. {@code processList}) are
     *  dropped + debug-logged. */
    private Map<String, Object> projectMemory(Map<?, ?> memMap, String path) {
        requireKeysPresent(memMap, MEMORY_ALLOWED_KEYS, path);
        requireNonNegative(memMap.get("totalPhysicalBytes"), path + ".totalPhysicalBytes");
        requireNonNegative(memMap.get("availableBytes"), path + ".availableBytes");
        requirePercent(memMap.get("usedPercent"), path + ".usedPercent");
        requireBoolean(memMap.get("highPressureWarning"), path + ".highPressureWarning");
        requireNonNegative(memMap.get("commitLimitBytes"), path + ".commitLimitBytes");
        requireNonNegative(memMap.get("commitUsedBytes"), path + ".commitUsedBytes");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalPhysicalBytes", memMap.get("totalPhysicalBytes"));
        out.put("availableBytes", memMap.get("availableBytes"));
        out.put("usedPercent", memMap.get("usedPercent"));
        out.put("highPressureWarning", memMap.get("highPressureWarning"));
        out.put("commitLimitBytes", memMap.get("commitLimitBytes"));
        out.put("commitUsedBytes", memMap.get("commitUsedBytes"));
        logDroppedKeys(stringKeys(memMap), MEMORY_ALLOWED_KEYS, path);
        return out;
    }

    /** Validate required {@code uptime} subfields + project the canonical
     *  four-key object. Unknown uptime keys (e.g. {@code localBootTime})
     *  are dropped + debug-logged. */
    private Map<String, Object> projectUptime(Map<?, ?> upMap, String path) {
        requireKeysPresent(upMap, UPTIME_ALLOWED_KEYS, path);
        requireNonNegative(upMap.get("lastBootEpochSec"), path + ".lastBootEpochSec");
        requireNonNegative(upMap.get("uptimeSeconds"), path + ".uptimeSeconds");
        requireNonNegative(upMap.get("uptimeDays"), path + ".uptimeDays");
        requireBoolean(upMap.get("longUptimeWarning"), path + ".longUptimeWarning");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lastBootEpochSec", upMap.get("lastBootEpochSec"));
        out.put("uptimeSeconds", upMap.get("uptimeSeconds"));
        out.put("uptimeDays", upMap.get("uptimeDays"));
        out.put("longUptimeWarning", upMap.get("longUptimeWarning"));
        logDroppedKeys(stringKeys(upMap), UPTIME_ALLOWED_KEYS, path);
        return out;
    }

    /**
     * Validate + project a single {@code probeErrors[]} element. {@code code}
     * is required + enum-checked; {@code source} (if present) must be the
     * {@code win32|none} enum; {@code summary} (if present) is bounded to
     * {@link #SUMMARY_MAX_LEN} HERE (so the command-result payload is also
     * bounded, not just the snapshot scalar) after value-redaction. Unknown
     * keys (e.g. {@code rawOutput}, {@code stackTrace}) are dropped +
     * debug-logged.
     */
    private Map<String, Object> projectProbeError(Map<?, ?> errMap, String errPath) {
        Object code = errMap.get("code");
        if (code == null) {
            throw new IllegalArgumentException(
                    "Missing required probeError code at " + errPath + ".code");
        }
        String codeStr = String.valueOf(code);
        if (!PROBE_ERROR_CODES.contains(codeStr)) {
            throw new IllegalArgumentException(
                    "Unsupported probeError code '" + codeStr + "' at "
                            + errPath + ".code");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Object source = errMap.get("source");
        if (source != null) {
            String src = String.valueOf(source);
            if (!ACCEPTED_SOURCE_USED.contains(src)) {
                throw new IllegalArgumentException(
                        "Unsupported probeError source '" + src + "' at "
                                + errPath + ".source (expected win32 | none)");
            }
            out.put("source", src);
        }
        out.put("code", codeStr);
        Object summary = errMap.get("summary");
        if (summary != null) {
            // Value-redaction already ran in rejectSecrets(); re-apply the
            // identifier strip on the projected scalar so a stripped path
            // is reflected, then bound the length.
            String s = redactStringValue(String.valueOf(summary), errPath + ".summary");
            if (s.length() > SUMMARY_MAX_LEN) {
                s = s.substring(0, SUMMARY_MAX_LEN);
            }
            out.put("summary", s);
        }
        logDroppedKeys(stringKeys(errMap), PROBE_ERROR_ALLOWED_KEYS, errPath);
        return out;
    }

    /**
     * Recursively walk the RAW sub-tree rejecting forbidden secret keys
     * and secret value patterns BEFORE the allowlist projection drops any
     * off-contract key. A secret in a soon-to-be-dropped key must still
     * fail-closed the entire result.
     */
    private void rejectSecrets(Object node, String path) {
        if (node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
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
                rejectSecrets(entry.getValue(), childPath);
            }
            return;
        }
        if (node instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object element : iterable) {
                rejectSecrets(element, path + "[" + (i++) + "]");
            }
            return;
        }
        if (node instanceof String s) {
            assertNoSecretValue(s, path);
        }
    }

    /** Fail-closed reject a string scalar carrying a secret value pattern
     *  (Bearer / JWT / kv leak). */
    private static void assertNoSecretValue(String value, String path) {
        if (value == null || value.isEmpty()) {
            return;
        }
        for (Pattern pattern : SECRET_VALUE_PATTERNS) {
            if (pattern.matcher(value).find()) {
                throw new IllegalArgumentException(
                        "Secret value pattern detected at " + path
                                + " — tokens / passwords / bearer headers must not appear"
                                + " in device-health payloads.");
            }
        }
    }

    /**
     * Apply identifier-level redaction to a string scalar (user paths,
     * SIDs, machine GUIDs replaced with {@value #REDACTED}). Secret value
     * patterns were already fail-closed rejected by {@link #rejectSecrets}.
     * The contract says a probeError summary must never be a path; if an
     * off-contract agent embeds one, we neutralize it rather than persist.
     */
    private String redactStringValue(String value, String path) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // Defense-in-depth: also reject here (a value reaching this method
        // outside the rejectSecrets walk still must not leak a secret).
        assertNoSecretValue(value, path);
        String out = value;
        out = USERS_PATH.matcher(out).replaceAll(REDACTED);
        out = UNIX_USER_PATH.matcher(out).replaceAll(REDACTED);
        out = WINDOWS_SID.matcher(out).replaceAll(REDACTED);
        out = MACHINE_GUID.matcher(out).replaceAll(REDACTED);
        return out;
    }

    /** Require every key in {@code requiredKeys} present + non-null in the
     *  map. Used for {@code memory}/{@code uptime} required subfields. */
    private static void requireKeysPresent(Map<?, ?> map, Set<String> requiredKeys, String path) {
        for (String req : requiredKeys) {
            if (!map.containsKey(req) || map.get(req) == null) {
                throw new IllegalArgumentException(
                        "Missing required device-health field '" + req + "' at "
                                + path + "." + req
                                + " — required v1 subfields are fail-closed.");
            }
        }
    }

    private static void requireBoolean(Object value, String path) {
        if (value instanceof Boolean) {
            return;
        }
        throw new IllegalArgumentException(
                "Expected boolean at " + path + " but got "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private static void requireNonNegative(Object value, String path) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Expected number at " + path + " but got null");
        }
        Number n = toNumber(value, path);
        if (n != null && n.longValue() < 0) {
            throw new IllegalArgumentException(
                    "Negative value not allowed at " + path + ": " + value);
        }
    }

    private static void requirePercent(Object value, String path) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Expected percent at " + path + " but got null");
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

    /** Collect the string-typed keys of a map (for drop-logging). */
    private static Set<String> stringKeys(Map<?, ?> map) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (Object k : map.keySet()) {
            keys.add(String.valueOf(k));
        }
        return keys;
    }

    /** Debug-log any key in {@code present} not in {@code allowed} (dropped
     *  by the projection). Forward-compat: ignore unknown, do not persist. */
    private static void logDroppedKeys(Set<String> present, Set<String> allowed, String path) {
        if (!log.isDebugEnabled()) {
            return;
        }
        for (String key : present) {
            if (!allowed.contains(key)) {
                log.debug("Dropping off-contract device-health key '{}' at {} (forward-compat: ignored, not persisted)",
                        key, path + "." + key);
            }
        }
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
