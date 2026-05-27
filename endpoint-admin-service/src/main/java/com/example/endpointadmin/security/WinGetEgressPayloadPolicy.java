package com.example.endpointadmin.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BE-021A — fail-closed schema + PII policy for the agent
 * {@code inventory.wingetEgress} block produced by AG-026A (Faz 22.5).
 *
 * <p>Runs <strong>before</strong> the parent
 * {@code endpoint_command_results} row is persisted, immediately after
 * the existing {@link SoftwareInventoryPayloadPolicy} pass (Codex
 * 019e6b88 plan-time AGREE). Failure throws
 * {@link IllegalArgumentException}; the
 * {@code EndpointAgentCommandService.submitResult} caller translates
 * that into a 400 response via the existing handler convention.
 *
 * <p>Two-layer enforcement:
 *
 * <ol>
 *   <li><b>Schema allowlist</b>: the {@code wingetEgress} block is
 *       version-pinned to {@link #ACCEPTED_SCHEMA_VERSION} (AG-026A
 *       schema=1). Unknown top-level keys are rejected so a future
 *       agent build that adds a field gets a fail-closed signal
 *       instead of silently widening the persisted JSONB. Sub-shapes
 *       ({@code sources[]}, {@code packageQuery}, {@code egress})
 *       carry their own allowlists.</li>
 *   <li><b>PII / secret scan</b>: every string value in the egress
 *       sub-tree is re-checked through the same regex set
 *       {@link SoftwareInventoryPayloadPolicy} uses for the
 *       {@code software} block (raw MSI GUID, {@code C:\\Users\\...}
 *       paths, Windows SID literals, forbidden keys like
 *       {@code licenseKey} / {@code token} / {@code password}). The
 *       agent redacts these before shipping, but backend revalidates
 *       fail-closed because BE-020I + BE-021A operate on
 *       defence-in-depth: agent compromise must not turn into a
 *       persisted-PII regression.</li>
 * </ol>
 *
 * <p>Allowlist (top-level + recursive children) tracks the canonical
 * AG-026A wire shape:
 *
 * <pre>{@code
 * wingetEgress
 *   ├─ supported              (bool, required)
 *   ├─ schemaVersion          (int, required, == ACCEPTED_SCHEMA_VERSION)
 *   ├─ probeDurationMs        (number, required)
 *   ├─ timeout                (bool, required)
 *   ├─ probeError             (string, optional)
 *   ├─ sources                (array<SourceInfo>, optional)
 *   ├─ sourceListError        (string, optional)
 *   ├─ packageQuery           (PackageQueryResult, required)
 *   └─ egress                 (EgressSummary, required)
 *
 * SourceInfo: name, argument, type, trustLevel, explicit
 * PackageQueryResult: packageId, found, exitCode, durationMs, timeout, errorReason
 * NetworkCheck: target, ok, durationMs, errorReason
 * EgressSummary: dns[], tcp[], https[], proxyConfigured, proxyUrl
 * }</pre>
 */
@Component
public class WinGetEgressPayloadPolicy {

    /**
     * AG-026A canonical schema version. A future bump SHOULD ship as a
     * paired backend change that relaxes this validator AND the V9
     * Flyway CHECK constraint, so the contract stays authoritative
     * for the live agent fleet.
     */
    public static final int ACCEPTED_SCHEMA_VERSION = 1;

    private static final Set<String> WINGET_EGRESS_KEYS = Set.of(
            "supported",
            "schemaVersion",
            "probeDurationMs",
            "timeout",
            "probeError",
            "sources",
            "sourceListError",
            "packageQuery",
            "egress");

    private static final Set<String> SOURCE_INFO_KEYS = Set.of(
            "name",
            "argument",
            "type",
            "trustLevel",
            "explicit");

    private static final Set<String> PACKAGE_QUERY_KEYS = Set.of(
            "packageId",
            "found",
            "exitCode",
            "durationMs",
            "timeout",
            "errorReason");

    private static final Set<String> EGRESS_SUMMARY_KEYS = Set.of(
            "dns",
            "tcp",
            "https",
            "proxyConfigured",
            "proxyUrl");

    private static final Set<String> NETWORK_CHECK_KEYS = Set.of(
            "target",
            "ok",
            "durationMs",
            "errorReason");

    private final SoftwareInventoryPayloadPolicy softwareInventoryPolicy;

    @Autowired
    public WinGetEgressPayloadPolicy(SoftwareInventoryPayloadPolicy softwareInventoryPolicy) {
        this.softwareInventoryPolicy = softwareInventoryPolicy;
    }

    /**
     * Validate the {@code inventory.wingetEgress} sub-map.
     *
     * <p>Caller responsibility: extract the {@code wingetEgress} value
     * from the {@code details.inventory} parent map and pass it here.
     * The parent {@code SoftwareInventoryPayloadPolicy} pass runs over
     * the entire {@code details} tree first; this validator focuses
     * on the egress-specific schema invariants.
     *
     * @param wingetEgress the {@code inventory.wingetEgress} sub-map,
     *                     or {@code null} if the agent did not ship the
     *                     block (which is the BE-020I-only legacy path).
     */
    public void validate(Object wingetEgress) {
        if (wingetEgress == null) {
            return;
        }
        if (!(wingetEgress instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException(
                    "wingetEgress must be an object, got "
                            + wingetEgress.getClass().getSimpleName());
        }
        // Defence-in-depth: re-run the parent PII scan over this subtree
        // even though the caller is expected to have validated the full
        // tree. A future caller that calls this validator directly
        // (e.g. in a unit test) gets the full coverage.
        softwareInventoryPolicy.validate(root);

        assertOnlyKnownKeys(root, WINGET_EGRESS_KEYS, "$.inventory.wingetEgress");
        assertSchemaVersionPinned(root.get("schemaVersion"));

        Object sources = root.get("sources");
        if (sources instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object element : iterable) {
                String path = "$.inventory.wingetEgress.sources[" + (i++) + "]";
                if (!(element instanceof Map<?, ?> source)) {
                    throw new IllegalArgumentException(
                            "wingetEgress.sources element must be an object at " + path);
                }
                assertOnlyKnownKeys(source, SOURCE_INFO_KEYS, path);
            }
        } else if (sources != null) {
            throw new IllegalArgumentException(
                    "wingetEgress.sources must be an array, got "
                            + sources.getClass().getSimpleName());
        }

        Object packageQuery = root.get("packageQuery");
        if (packageQuery != null) {
            if (!(packageQuery instanceof Map<?, ?> pq)) {
                throw new IllegalArgumentException(
                        "wingetEgress.packageQuery must be an object, got "
                                + packageQuery.getClass().getSimpleName());
            }
            assertOnlyKnownKeys(pq, PACKAGE_QUERY_KEYS, "$.inventory.wingetEgress.packageQuery");
        }

        Object egress = root.get("egress");
        if (egress != null) {
            if (!(egress instanceof Map<?, ?> es)) {
                throw new IllegalArgumentException(
                        "wingetEgress.egress must be an object, got "
                                + egress.getClass().getSimpleName());
            }
            assertOnlyKnownKeys(es, EGRESS_SUMMARY_KEYS, "$.inventory.wingetEgress.egress");
            assertNetworkCheckList(es.get("dns"), "dns");
            assertNetworkCheckList(es.get("tcp"), "tcp");
            assertNetworkCheckList(es.get("https"), "https");
        }
    }

    private void assertSchemaVersionPinned(Object schemaVersion) {
        if (schemaVersion == null) {
            throw new IllegalArgumentException(
                    "wingetEgress.schemaVersion is required (expected "
                            + ACCEPTED_SCHEMA_VERSION + ").");
        }
        int actual;
        if (schemaVersion instanceof Number n) {
            actual = n.intValue();
        } else if (schemaVersion instanceof String s) {
            try {
                actual = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "wingetEgress.schemaVersion must be an integer, got " + s);
            }
        } else {
            throw new IllegalArgumentException(
                    "wingetEgress.schemaVersion must be an integer, got "
                            + schemaVersion.getClass().getSimpleName());
        }
        if (actual != ACCEPTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "wingetEgress.schemaVersion = " + actual
                            + " not supported (expected "
                            + ACCEPTED_SCHEMA_VERSION + ").");
        }
    }

    private void assertNetworkCheckList(Object list, String fieldName) {
        if (list == null) {
            return;
        }
        if (!(list instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException(
                    "wingetEgress.egress." + fieldName
                            + " must be an array, got "
                            + list.getClass().getSimpleName());
        }
        int i = 0;
        for (Object element : iterable) {
            String path = "$.inventory.wingetEgress.egress." + fieldName + "[" + (i++) + "]";
            if (!(element instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException(
                        "wingetEgress.egress." + fieldName
                                + " element must be an object at " + path);
            }
            assertOnlyKnownKeys(entry, NETWORK_CHECK_KEYS, path);
        }
    }

    private void assertOnlyKnownKeys(Map<?, ?> map, Set<String> allowed, String path) {
        for (Object rawKey : map.keySet()) {
            String key = String.valueOf(rawKey);
            if (!allowed.contains(key)) {
                String keyLower = key.toLowerCase(Locale.ROOT);
                // Case-insensitive double-check covers a future field
                // renamed via case (e.g. "PackageQuery"). Even then,
                // unknown is unknown.
                boolean caseInsensitiveMatch = allowed.stream()
                        .anyMatch(known -> known.toLowerCase(Locale.ROOT).equals(keyLower));
                if (!caseInsensitiveMatch) {
                    throw new IllegalArgumentException(
                            "Unknown wingetEgress field '" + key + "' at " + path);
                }
            }
        }
    }
}
