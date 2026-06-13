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
 * "don't trust the agent" gate: when a COLLECT_BACKUP_DRYRUN result arrives
 * it re-validates the manifest against the SAME contract the agent enforces,
 * so a buggy or compromised agent cannot persist a manifest that violates the
 * privacy boundary. The backend has no device filesystem, so the mirror is a
 * STRUCTURAL / INVARIANT re-validation (not a re-walk):
 *
 * <ul>
 *   <li><b>Path-free</b> (KVKK m.4 data-minimization): no raw filesystem path
 *       may appear in ANY field — recursive scan rejects backslash, drive
 *       prefixes ({@code C:}), and {@code ..} traversal.</li>
 *   <li><b>Denylist-negative</b>: no entry may be a DC-EA-RED class. Entries
 *       are eligible files only; {@code extension_type} is a bounded enum that
 *       <b>excludes</b> {@code archive} (archives are denied-aggregate, never
 *       an entry — the contract v1 P0 amendment, Codex 019ec28a).</li>
 *   <li><b>Allowlist-match</b>: {@code path_class} is the bounded class enum;
 *       {@code root_ref} is an opaque {@code managed_root:<token>} (positive
 *       allowlist, mirrors the agent's validRootRef).</li>
 *   <li><b>Aggregate consistency</b>: non-negative counts; {@code
 *       denied_classes} ⊆ the 10 RED classes; {@code total_eligible_count} ==
 *       Σ entry.file_count; {@code container_count} ≤ {@code denied_count};
 *       {@code archive_container} ∈ denied_classes ⟺ {@code container_count} ≥ 1.</li>
 *   <li><b>Device/tenant binding</b>: the manifest's {@code device_id} /
 *       {@code tenant_id} must match the command's device/tenant (a
 *       compromised agent cannot submit another device's manifest).</li>
 * </ul>
 *
 * <p>Any violation throws {@link IllegalArgumentException} with a PATH-FREE
 * message; the caller maps it to a 400 reject + bounded command error
 * (mirrors the INSTALL/UNINSTALL evidence-policy flow).
 */
@Component
public class BackupDryRunManifestPayloadPolicy {

    /** Result-details key the agent uses for the manifest (Go executor: Details["backupDryRun"]). */
    public static final String MANIFEST_KEY = "backupDryRun";

    public static final String PINNED_VERSION = "1";
    public static final String PINNED_TIER = "DC-EA-1";

    private static final String ROOT_REF_PREFIX = "managed_root:";

    /** Bounded path_class enum (contract §2). */
    private static final Set<String> PATH_CLASSES = Set.of(
            "managed/onedrive-business",
            "managed/sharepoint",
            "managed/unc-corp",
            "managed/it-folder",
            "mdm-gpo-root");

    /** Bounded extension_type enum (contract §2) — archive INTENTIONALLY ABSENT. */
    private static final Set<String> EXTENSION_TYPES = Set.of(
            "doc", "sheet", "pdf", "image", "other");

    private static final Set<String> MTIME_BUCKETS = Set.of("P7D", "P30D", "P90D", "older");

    private static final Set<String> OWNER_SCOPES = Set.of("company", "unknown");

    /** The 10 DC-EA-RED classes (contract §3) — the ONLY values denied_classes may contain. */
    private static final Set<String> RED_CLASSES = Set.of(
            "credential_store", "browser_profile", "mailbox_cache", "private_key_material",
            "cloud_cli_token_store", "password_manager_vault", "dpapi_store", "registry_hive",
            "app_token_store", "archive_container");

    /** A bare drive prefix like {@code C:} — never legitimate in this manifest. */
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:");

    /**
     * Validate the manifest carried under {@code details.backupDryRun}. {@code
     * expectedDeviceId} / {@code expectedTenantId} bind the manifest to the
     * command's device/tenant (pass null to skip the binding check in pure
     * contract unit tests).
     *
     * @throws IllegalArgumentException (path-free message) on any violation
     */
    public void validate(Map<String, Object> details, String expectedDeviceId, String expectedTenantId) {
        if (details == null) {
            throw reject("missing result details");
        }
        Object raw = details.get(MANIFEST_KEY);
        if (!(raw instanceof Map<?, ?> manifest)) {
            throw reject("missing or non-object backup dry-run manifest");
        }

        // Belt-and-suspenders path-free scan over the ENTIRE manifest tree
        // BEFORE field-level checks, so a path leaked into any field (even one
        // not enumerated below) is rejected.
        assertPathFree(manifest);

        requireEquals(manifest, "manifest_version", PINNED_VERSION);
        requireEquals(manifest, "dc_ea_tier", PINNED_TIER);

        // Device / tenant binding (no-trust).
        if (expectedDeviceId != null && !expectedDeviceId.equals(stringField(manifest, "device_id"))) {
            throw reject("manifest device_id does not match command device");
        }
        if (expectedTenantId != null && !expectedTenantId.equals(stringField(manifest, "tenant_id"))) {
            throw reject("manifest tenant_id does not match command tenant");
        }

        // Scope.
        if (!(manifest.get("scope") instanceof Map<?, ?> scope)) {
            throw reject("missing scope object");
        }
        if (!(scope.get("managed_data_roots") instanceof List<?> roots)) {
            throw reject("scope.managed_data_roots not a list");
        }
        for (Object r : roots) {
            if (!(r instanceof String s) || !validRootRef(s)) {
                throw reject("scope contains a non-opaque root_ref");
            }
        }
        if (scope.containsKey("byod") && !(scope.get("byod") instanceof Boolean)) {
            throw reject("scope.byod not a boolean");
        }

        // Entries.
        long sumFileCount = 0L;
        Object entriesRaw = manifest.get("entries");
        if (entriesRaw != null && !(entriesRaw instanceof List)) {
            throw reject("entries not a list");
        }
        if (entriesRaw instanceof List<?> entries) {
            for (Object e : entries) {
                if (!(e instanceof Map<?, ?> entry)) {
                    throw reject("entry not an object");
                }
                sumFileCount += validateEntry(entry);
            }
        }

        // Aggregate.
        if (!(manifest.get("aggregate") instanceof Map<?, ?> aggregate)) {
            throw reject("missing aggregate object");
        }
        validateAggregate(aggregate, sumFileCount);
    }

    private long validateEntry(Map<?, ?> entry) {
        requireEnum(entry, "path_class", PATH_CLASSES);

        String ref = stringField(entry, "root_ref");
        if (!validRootRef(ref)) {
            throw reject("entry root_ref not an opaque managed_root token");
        }

        String ext = stringField(entry, "extension_type");
        if (!EXTENSION_TYPES.contains(ext)) {
            // CRITICAL: archive / archive_container must NEVER be an entry —
            // archives are denied-aggregate (contract §1.2/§3 P0 amendment).
            throw reject("entry extension_type out of bounded enum");
        }

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

        Object dcRaw = aggregate.get("denied_classes");
        if (dcRaw != null && !(dcRaw instanceof List)) {
            throw reject("denied_classes not a list");
        }
        boolean hasArchiveContainer = false;
        if (dcRaw instanceof List<?> deniedClasses) {
            for (Object c : deniedClasses) {
                if (!(c instanceof String s) || !RED_CLASSES.contains(s)) {
                    throw reject("denied_classes contains an unknown class");
                }
                if ("archive_container".equals(c)) {
                    hasArchiveContainer = true;
                }
            }
        }
        // archive_container in denied_classes ⟺ container_count >= 1.
        if (hasArchiveContainer && containers < 1) {
            throw reject("archive_container denied but container_count is zero");
        }
        if (containers >= 1 && !hasArchiveContainer) {
            throw reject("container_count positive but archive_container missing from denied_classes");
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

    /** Recursive path-free scan: reject backslash, drive prefix, or dotdot in any string. */
    private void assertPathFree(Object node) {
        if (node instanceof String s) {
            if (s.indexOf('\\') >= 0) {
                throw reject("manifest field contains a path separator");
            }
            if (s.contains("..")) {
                throw reject("manifest field contains path traversal");
            }
            if (DRIVE_PREFIX.matcher(s).find()) {
                throw reject("manifest field contains a drive-letter path");
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                assertPathFree(v);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                assertPathFree(v);
            }
        }
    }

    private static String stringField(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s)) {
            throw reject("manifest field '" + key + "' missing or not a string");
        }
        return s;
    }

    private static long longField(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Number n)) {
            throw reject("manifest field '" + key + "' missing or not a number");
        }
        return n.longValue();
    }

    private static void requireEquals(Map<?, ?> m, String key, String expected) {
        if (!expected.equals(m.get(key))) {
            throw reject("manifest field '" + key + "' has an unexpected value");
        }
    }

    private static void requireEnum(Map<?, ?> m, String key, Set<String> allowed) {
        Object v = m.get(key);
        if (!(v instanceof String s) || !allowed.contains(s)) {
            throw reject("manifest field '" + key + "' out of bounded enum");
        }
    }

    private static IllegalArgumentException reject(String reason) {
        // Reasons are intentionally path-free + value-free (only field NAMES /
        // contract labels), so the bounded command error can never leak a path.
        return new IllegalArgumentException("BACKUP_DRYRUN_MANIFEST_VIOLATION: " + reason);
    }
}
