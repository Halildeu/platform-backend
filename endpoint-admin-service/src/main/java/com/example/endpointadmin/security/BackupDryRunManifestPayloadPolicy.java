package com.example.endpointadmin.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BE — server-side MIRROR VALIDATOR for the Faz 22.8A endpoint backup
 * DRY-RUN manifest (contract §5; gitops
 * docs/faz-22-8a-backup-manifest-contract-v1.md, merged gitops PR #1530).
 *
 * <p>The agent produces a METADATA-ONLY manifest (path-class / size /
 * mtime-bucket / count — no file content, no hash). This policy is the
 * "don't trust the agent" gate: when a COLLECT_BACKUP_DRYRUN result arrives it
 * re-validates the manifest against the SAME contract, so a buggy or
 * compromised agent cannot persist a manifest that breaks the privacy
 * boundary. The backend has no device filesystem, so the mirror is a STRICT
 * STRUCTURAL re-validation (not a re-walk):
 *
 * <ul>
 *   <li><b>Strict schema / whitelist</b> (Codex 019ec2e6 P0): exact allowed
 *       key set at EVERY object level (details, manifest, scope, entry,
 *       aggregate) — an unknown field (e.g. {@code content_sha256}, {@code
 *       hash}, {@code rawPath}) or a path-shaped MAP KEY is rejected. This is
 *       how "metadata-only / no content / no hash" is server-enforced: a field
 *       that would carry content/hash simply isn't in the allowed set.</li>
 *   <li><b>Full-envelope path-free</b> (KVKK m.4): no raw filesystem path may
 *       appear in ANY string — keys AND values, recursively — nor in the
 *       result {@code summary} / {@code errorCode} / {@code errorMessage}.
 *       Rejects backslash, drive paths ({@code C:\}, {@code C:/}, {@code
 *       D:relative}, embedded), and {@code ..} traversal.</li>
 *   <li><b>Denylist-negative</b>: no entry may be a DC-EA-RED class.
 *       {@code extension_type} is a bounded enum that EXCLUDES {@code archive}
 *       (archives are denied-aggregate, never an entry — contract v1 P0
 *       amendment, Codex 019ec28a).</li>
 *   <li><b>Allowlist-match</b>: {@code path_class} bounded enum; {@code
 *       root_ref} opaque {@code managed_root:<token>} positive allowlist.</li>
 *   <li><b>Integral aggregate invariants</b>: integer-typed counts only (no
 *       decimal truncation); {@code total_eligible_count} == Σ entry.file_count;
 *       {@code container_count} ≤ {@code denied_count}; {@code denied_classes}
 *       ⊆ the 10 RED classes; {@code archive_container} ∈ denied_classes ⟺
 *       {@code container_count} ≥ 1.</li>
 *   <li><b>Device/tenant binding</b>: the manifest's {@code device_id} /
 *       {@code tenant_id} must match the command's device/tenant.</li>
 * </ul>
 *
 * <p>Any violation throws {@link IllegalArgumentException} with a PATH-FREE,
 * VALUE-FREE message (only field names / contract labels); the caller maps it
 * to a 400 reject + bounded command error.
 */
@Component
public class BackupDryRunManifestPayloadPolicy {

    public static final String MANIFEST_KEY = "backupDryRun";
    public static final String PINNED_VERSION = "1";
    public static final String PINNED_TIER = "DC-EA-1";

    private static final String ROOT_REF_PREFIX = "managed_root:";

    // Exact allowed key sets — additionalProperties:false at every level.
    private static final Set<String> DETAILS_KEYS = Set.of(MANIFEST_KEY);
    private static final Set<String> MANIFEST_KEYS = Set.of(
            "manifest_version", "dc_ea_tier", "device_id", "tenant_id",
            "generated_at", "allowlist_profile_id", "scope", "entries", "aggregate");
    private static final Set<String> SCOPE_KEYS = Set.of("managed_data_roots", "byod");
    private static final Set<String> ENTRY_KEYS = Set.of(
            "path_class", "root_ref", "relative_depth", "extension_type",
            "size_bytes", "mtime_bucket", "owner_scope_marker", "file_count");
    private static final Set<String> AGGREGATE_KEYS = Set.of(
            "total_eligible_count", "total_eligible_size_bytes", "denied_count",
            "denied_classes", "container_count", "unresolved_path_count");

    private static final Set<String> PATH_CLASSES = Set.of(
            "managed/onedrive-business", "managed/sharepoint", "managed/unc-corp",
            "managed/it-folder", "mdm-gpo-root");
    private static final Set<String> EXTENSION_TYPES = Set.of(
            "doc", "sheet", "pdf", "image", "other"); // archive INTENTIONALLY ABSENT
    private static final Set<String> MTIME_BUCKETS = Set.of("P7D", "P30D", "P90D", "older");
    private static final Set<String> OWNER_SCOPES = Set.of("company", "unknown");
    private static final Set<String> RED_CLASSES = Set.of(
            "credential_store", "browser_profile", "mailbox_cache", "private_key_material",
            "cloud_cli_token_store", "password_manager_vault", "dpapi_store", "registry_hive",
            "app_token_store", "archive_container");

    // A Windows drive path ANYWHERE in a string: a STANDALONE single drive
    // letter (start-of-string or preceded by a non-letter, so "managed_root:"
    // and ISO timestamps like "...T21:00" do NOT match) followed by a colon.
    // Catches "C:\\x", "C:/x", embedded "failed at C:/Users", and drive-relative
    // "D:relative" (Codex 019ec2e6 P0#2).
    private static final Pattern DRIVE_PATH = Pattern.compile("(^|[^A-Za-z])[A-Za-z]:");

    /**
     * Validate the COLLECT_BACKUP_DRYRUN result. {@code details} MUST be
     * non-null and contain EXACTLY the manifest (no sibling keys). {@code
     * summary} / {@code errorMessage} are path-free scanned (full envelope).
     * {@code expectedDeviceId} / {@code expectedTenantId} bind the manifest to
     * the command (pass null to skip the binding in pure contract unit tests).
     *
     * @throws IllegalArgumentException (path-free message) on any violation
     */
    public void validate(Map<String, Object> details, String summary, String errorCode, String errorMessage,
                         String expectedDeviceId, String expectedTenantId) {
        // P0 (Codex 019ec2e6): a COLLECT_BACKUP_DRYRUN result with null details
        // must FAIL, not silently skip — the manifest is mandatory.
        if (details == null) {
            throw reject("result details missing (manifest required)");
        }
        // Full-envelope path-free: the result summary / errorCode / errorMessage
        // also persist (endpoint_command_results + admin DTO), so a raw path in
        // any of them is rejected too (Codex 019ec2e6 P0#1).
        assertStringPathFree(summary);
        assertStringPathFree(errorCode);
        assertStringPathFree(errorMessage);

        // Top-level details MUST be exactly { backupDryRun } — no sibling keys
        // (which would otherwise persist verbatim into the result payload).
        requireExactKeys(details, DETAILS_KEYS);
        Object raw = details.get(MANIFEST_KEY);
        if (!(raw instanceof Map<?, ?> manifest)) {
            throw reject("manifest is not an object");
        }

        // Belt-and-suspenders recursive path-free scan over the whole manifest
        // tree — KEYS and VALUES — before field-level checks.
        assertPathFree(manifest);

        requireExactKeys(manifest, MANIFEST_KEYS);
        requireEquals(manifest, "manifest_version", PINNED_VERSION);
        requireEquals(manifest, "dc_ea_tier", PINNED_TIER);
        // device_id / tenant_id / allowlist_profile_id must be strings (present
        // via the exact-key check); device/tenant bind to the command.
        String deviceId = stringField(manifest, "device_id");
        String tenantId = stringField(manifest, "tenant_id");
        stringField(manifest, "allowlist_profile_id");
        validateIso8601(stringField(manifest, "generated_at"));
        if (expectedDeviceId != null && !expectedDeviceId.equals(deviceId)) {
            throw reject("manifest device_id does not match command device");
        }
        if (expectedTenantId != null && !expectedTenantId.equals(tenantId)) {
            throw reject("manifest tenant_id does not match command tenant");
        }

        if (!(manifest.get("scope") instanceof Map<?, ?> scope)) {
            throw reject("scope is not an object");
        }
        requireExactKeys(scope, SCOPE_KEYS);
        if (!(scope.get("managed_data_roots") instanceof List<?> roots)) {
            throw reject("scope.managed_data_roots not a list");
        }
        for (Object r : roots) {
            if (!(r instanceof String s) || !validRootRef(s)) {
                throw reject("scope contains a non-opaque root_ref");
            }
        }
        if (!(scope.get("byod") instanceof Boolean)) {
            throw reject("scope.byod not a boolean");
        }

        if (!(manifest.get("entries") instanceof List<?> entries)) {
            throw reject("entries not a list");
        }
        long sumFileCount = 0L;
        for (Object e : entries) {
            if (!(e instanceof Map<?, ?> entry)) {
                throw reject("entry not an object");
            }
            sumFileCount += validateEntry(entry);
        }

        if (!(manifest.get("aggregate") instanceof Map<?, ?> aggregate)) {
            throw reject("aggregate not an object");
        }
        validateAggregate(aggregate, sumFileCount);
    }

    private long validateEntry(Map<?, ?> entry) {
        requireExactKeys(entry, ENTRY_KEYS);
        requireEnum(entry, "path_class", PATH_CLASSES);
        if (!validRootRef(stringField(entry, "root_ref"))) {
            throw reject("entry root_ref not an opaque managed_root token");
        }
        // CRITICAL: archive / archive_container can never be an entry — they
        // are denied-aggregate (contract §1.2/§3 P0 amendment).
        requireEnum(entry, "extension_type", EXTENSION_TYPES);
        requireEnum(entry, "mtime_bucket", MTIME_BUCKETS);
        requireEnum(entry, "owner_scope_marker", OWNER_SCOPES);
        if (longField(entry, "size_bytes") < 0) {
            throw reject("entry size_bytes negative");
        }
        if (longField(entry, "relative_depth") < 0) {
            throw reject("entry relative_depth negative");
        }
        long fileCount = longField(entry, "file_count");
        if (fileCount < 1) {
            throw reject("entry file_count < 1");
        }
        return fileCount;
    }

    private void validateAggregate(Map<?, ?> aggregate, long sumFileCount) {
        requireExactKeys(aggregate, AGGREGATE_KEYS);
        long totalCount = longField(aggregate, "total_eligible_count");
        long totalSize = longField(aggregate, "total_eligible_size_bytes");
        long denied = longField(aggregate, "denied_count");
        long containers = longField(aggregate, "container_count");
        long unresolved = longField(aggregate, "unresolved_path_count");
        for (long v : new long[] {totalCount, totalSize, denied, containers, unresolved}) {
            if (v < 0) {
                throw reject("negative aggregate count");
            }
        }
        if (totalCount != sumFileCount) {
            throw reject("total_eligible_count does not equal the sum of entry file_count");
        }
        if (containers > denied) {
            throw reject("container_count exceeds denied_count");
        }
        if (!(aggregate.get("denied_classes") instanceof List<?> deniedClasses)) {
            throw reject("denied_classes not a list");
        }
        boolean hasArchiveContainer = false;
        for (Object c : deniedClasses) {
            if (!(c instanceof String s) || !RED_CLASSES.contains(s)) {
                throw reject("denied_classes contains an unknown class");
            }
            if ("archive_container".equals(c)) {
                hasArchiveContainer = true;
            }
        }
        if (hasArchiveContainer && containers < 1) {
            throw reject("archive_container denied but container_count is zero");
        }
        if (containers >= 1 && !hasArchiveContainer) {
            throw reject("container_count positive but archive_container missing from denied_classes");
        }
        // denied_count == 0 ⟺ denied_classes empty (Codex 019ec2e6 hardening).
        if ((denied == 0) != deniedClasses.isEmpty()) {
            throw reject("denied_count and denied_classes disagree on whether denials exist");
        }
    }

    private static void validateIso8601(String s) {
        try {
            java.time.Instant.parse(s);
        } catch (RuntimeException e) {
            throw reject("generated_at is not an ISO-8601 instant");
        }
    }

    /** Positive allowlist mirroring the agent: token is {@code [A-Za-z0-9._-]+}. */
    private static boolean validRootRef(String s) {
        if (s == null || !s.startsWith(ROOT_REF_PREFIX)) {
            return false;
        }
        String id = s.substring(ROOT_REF_PREFIX.length());
        if (id.isEmpty()) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Recursive path-free scan over KEYS and VALUES (Codex 019ec2e6 P0#2). */
    private void assertPathFree(Object node) {
        if (node instanceof String s) {
            assertStringPathFree(s);
        } else if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> kv : map.entrySet()) {
                if (kv.getKey() instanceof String ks) {
                    assertStringPathFree(ks);
                }
                assertPathFree(kv.getValue());
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                assertPathFree(v);
            }
        }
    }

    private void assertStringPathFree(String s) {
        if (s == null) {
            return;
        }
        if (s.indexOf('\\') >= 0) {
            throw reject("a field contains a path separator");
        }
        if (s.contains("..")) {
            throw reject("a field contains path traversal");
        }
        if (DRIVE_PATH.matcher(s).find()) {
            throw reject("a field contains a drive-letter path");
        }
    }

    /** Reject any key not in the allowed set (additionalProperties:false). */
    private static void requireExactKeys(Map<?, ?> m, Set<String> allowed) {
        for (Object k : m.keySet()) {
            if (!(k instanceof String s) || !allowed.contains(s)) {
                throw reject("object carries an unexpected field");
            }
        }
        // Require every allowed key present (no missing structural field).
        for (String k : allowed) {
            if (!m.containsKey(k)) {
                throw reject("object missing a required field");
            }
        }
    }

    private static String stringField(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof String s)) {
            throw reject("field '" + key + "' missing or not a string");
        }
        return s;
    }

    /** Integer-typed only — rejects JSON decimals (no 1.9 -> 1 truncation). */
    private static long longField(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v instanceof Integer i) {
            return i.longValue();
        }
        if (v instanceof Long l) {
            return l;
        }
        throw reject("field '" + key + "' missing or not an integer");
    }

    private static void requireEquals(Map<?, ?> m, String key, String expected) {
        if (!expected.equals(m.get(key))) {
            throw reject("field '" + key + "' has an unexpected value");
        }
    }

    private static void requireEnum(Map<?, ?> m, String key, Set<String> allowed) {
        if (!(m.get(key) instanceof String s) || !allowed.contains(s)) {
            throw reject("field '" + key + "' out of bounded enum");
        }
    }

    private static IllegalArgumentException reject(String reason) {
        return new IllegalArgumentException("BACKUP_DRYRUN_MANIFEST_VIOLATION: " + reason);
    }
}
