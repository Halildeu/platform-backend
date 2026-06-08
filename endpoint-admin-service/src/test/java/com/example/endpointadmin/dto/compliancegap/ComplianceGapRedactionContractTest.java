package com.example.endpointadmin.dto.compliancegap;

import com.example.endpointadmin.service.ComplianceGapType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 22.7 COMPLETED-promotion gate (Codex {@code 019ea95d} finding 3):
 * machine-enforced redaction allowlist contract for the compliance-gap response.
 *
 * <p>{@link GapDetail#details()} and {@link ComplianceGapResponse#filterEcho()}
 * are {@code Map<String,Object>}, so a future change could leak personal /
 * sensitive data (ip, user_upn, SID, raw payload, full path, command line,
 * token, ...) onto the wire WITHOUT a compile error. This test serializes a
 * representative response with both MVP gap types and asserts:
 * <ol>
 *   <li>each level's field names stay within an exact allowlist, and</li>
 *   <li>no field name anywhere in the tree matches a sensitive deny-list.</li>
 * </ol>
 * It fails loudly the moment a leaky field bleeds into the contract — the
 * source services only emit allowlisted scalars today, so this is a
 * regression guard, not a bug reproduction.
 */
class ComplianceGapRedactionContractTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    /** Exact field names permitted at each response level. */
    private static final Set<String> TOP_LEVEL =
            Set.of("items", "total", "page", "pageSize", "filterEcho", "computedAt");
    private static final Set<String> DEVICE_ITEM =
            Set.of("deviceId", "deviceName", "lastSeen", "gapCount", "gapStrength", "gaps", "staleComponents");
    private static final Set<String> GAP =
            Set.of("type", "label", "sourceSnapshotCollectedAt", "stale", "details");
    private static final Set<String> GAP_DETAILS =
            Set.of("rdpEnabled", "pendingTotalCount");
    private static final Set<String> FILTER_ECHO =
            Set.of("gapTypes", "freshnessWindow", "page", "pageSize");

    /**
     * Sensitive field names that must NEVER appear anywhere in the serialized
     * response (case-insensitive exact field-name match). Mirrors the source
     * redaction policy (AG-038-be SUMMARY_VALUE_DENYLIST_RE / NAME_FULLPATH).
     */
    private static final Set<String> DENY = Set.of(
            "ip", "ipaddress", "ip_address", "macaddress", "mac_address",
            "userupn", "user_upn", "upn", "username", "user_name",
            "sid", "usersid", "user_sid", "serial", "serialnumber", "serial_number",
            "password", "passwd", "token", "bearer", "secret", "apikey", "api_key",
            "cookie", "fullpath", "full_path", "path", "commandline", "command_line",
            "args", "arguments", "probeerrors", "probe_errors", "publisher",
            "downloadurl", "download_url", "url", "licensekey", "license_key",
            "rawpayload", "raw_payload", "payload", "email"
    );

    @Test
    void responseSerializesOnlyAllowlistedFields() throws Exception {
        String json = MAPPER.writeValueAsString(representativeResponse());
        JsonNode root = MAPPER.readTree(json);

        // (a) structural allowlist — exact key set per level
        assertThat(fieldNames(root))
                .as("top-level response keys")
                .containsExactlyInAnyOrderElementsOf(TOP_LEVEL);

        JsonNode device = root.get("items").get(0);
        assertThat(fieldNames(device))
                .as("device-item keys")
                .containsExactlyInAnyOrderElementsOf(DEVICE_ITEM);

        for (JsonNode gap : device.get("gaps")) {
            assertThat(fieldNames(gap))
                    .as("gap keys")
                    .containsExactlyInAnyOrderElementsOf(GAP);
            assertThat(fieldNames(gap.get("details")))
                    .as("gap.details keys allowlist")
                    .allMatch(GAP_DETAILS::contains);
        }

        assertThat(fieldNames(root.get("filterEcho")))
                .as("filterEcho keys allowlist")
                .allMatch(FILTER_ECHO::contains);

        // (b) no sensitive field name anywhere in the tree
        Set<String> all = new TreeSet<>();
        collectFieldNames(root, all);
        Set<String> leaked = new TreeSet<>();
        for (String f : all) {
            if (DENY.contains(f.toLowerCase())) {
                leaked.add(f);
            }
        }
        assertThat(leaked)
                .as("compliance-gap response must never surface sensitive fields")
                .isEmpty();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void collectFieldNames(JsonNode node, Set<String> sink) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                sink.add(entry.getKey());
                collectFieldNames(entry.getValue(), sink);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectFieldNames(child, sink);
            }
        }
    }

    private static ComplianceGapResponse representativeResponse() {
        Instant now = Instant.parse("2026-06-09T00:00:00Z");
        GapDetail rdp = new GapDetail(
                ComplianceGapType.RDP_ENABLED.wire(),
                ComplianceGapType.RDP_ENABLED.label(),
                now, false, Map.of("rdpEnabled", true));
        GapDetail pending = new GapDetail(
                ComplianceGapType.PENDING_SECURITY_UPDATES.wire(),
                ComplianceGapType.PENDING_SECURITY_UPDATES.label(),
                now, false, Map.of("pendingTotalCount", 5));
        DeviceComplianceGap device = new DeviceComplianceGap(
                "22222222-2222-2222-2222-222222222222",
                "HALILKOOLUB735", now, 2, "strong",
                List.of(rdp, pending), List.of());
        Map<String, Object> filterEcho = Map.of(
                "gapTypes", List.of("pending_security_updates", "rdp_enabled"),
                "freshnessWindow", "PT168H",
                "page", 1,
                "pageSize", 50);
        return new ComplianceGapResponse(List.of(device), 1, 1, 50, filterEcho, now);
    }
}
