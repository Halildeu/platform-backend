package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the Faz 22.8A backup dry-run manifest MIRROR validator
 * (contract §5). No Spring / no Postgres — exercises every contract invariant
 * directly. The wired 400-reject path is covered in
 * EndpointAgentCommandServiceTest.
 */
class BackupDryRunManifestPayloadPolicyTest {

    private static final String DEVICE = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String ROOT_REF = "managed_root:33333333-3333-3333-3333-333333333333";

    private final BackupDryRunManifestPayloadPolicy policy = new BackupDryRunManifestPayloadPolicy();

    // ---- builders ---------------------------------------------------------

    private Map<String, Object> validEntry() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("path_class", "managed/it-folder");
        e.put("root_ref", ROOT_REF);
        e.put("relative_depth", 2);
        e.put("extension_type", "doc");
        e.put("size_bytes", 123);
        e.put("mtime_bucket", "P7D");
        e.put("owner_scope_marker", "company");
        e.put("file_count", 1);
        return e;
    }

    private Map<String, Object> validManifest() {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("managed_data_roots", new ArrayList<>(List.of(ROOT_REF)));
        scope.put("byod", false);

        List<Object> entries = new ArrayList<>();
        entries.add(validEntry());

        Map<String, Object> agg = new LinkedHashMap<>();
        agg.put("total_eligible_count", 1);
        agg.put("total_eligible_size_bytes", 123);
        agg.put("denied_count", 0);
        agg.put("denied_classes", new ArrayList<>());
        agg.put("container_count", 0);
        agg.put("unresolved_path_count", 0);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("manifest_version", "1");
        m.put("dc_ea_tier", "DC-EA-1");
        m.put("device_id", DEVICE);
        m.put("tenant_id", TENANT);
        m.put("allowlist_profile_id", "prof-1");
        m.put("scope", scope);
        m.put("entries", entries);
        m.put("aggregate", agg);
        return m;
    }

    private Map<String, Object> details(Map<String, Object> manifest) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("backupDryRun", manifest);
        return d;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstEntry(Map<String, Object> manifest) {
        return (Map<String, Object>) ((List<Object>) manifest.get("entries")).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> agg(Map<String, Object> manifest) {
        return (Map<String, Object>) manifest.get("aggregate");
    }

    private void expectReject(Map<String, Object> manifest) {
        assertThatThrownBy(() -> policy.validate(details(manifest), DEVICE, TENANT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("BACKUP_DRYRUN_MANIFEST_VIOLATION");
    }

    // ---- happy path -------------------------------------------------------

    @Test
    void validManifestPasses() {
        assertThatCode(() -> policy.validate(details(validManifest()), DEVICE, TENANT))
                .doesNotThrowAnyException();
    }

    @Test
    void nullBindingSkipsDeviceTenantCheck() {
        assertThatCode(() -> policy.validate(details(validManifest()), null, null))
                .doesNotThrowAnyException();
    }

    // ---- structural / header ---------------------------------------------

    @Test
    void missingManifestRejected() {
        assertThatThrownBy(() -> policy.validate(Map.of("other", "x"), DEVICE, TENANT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonObjectManifestRejected() {
        assertThatThrownBy(() -> policy.validate(details(null), DEVICE, TENANT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongVersionRejected() {
        Map<String, Object> m = validManifest();
        m.put("manifest_version", "2");
        expectReject(m);
    }

    @Test
    void wrongTierRejected() {
        Map<String, Object> m = validManifest();
        m.put("dc_ea_tier", "DC-EA-2");
        expectReject(m);
    }

    // ---- device / tenant binding -----------------------------------------

    @Test
    void deviceBindingMismatchRejected() {
        Map<String, Object> m = validManifest();
        m.put("device_id", "99999999-9999-9999-9999-999999999999");
        expectReject(m);
    }

    @Test
    void tenantBindingMismatchRejected() {
        Map<String, Object> m = validManifest();
        m.put("tenant_id", "99999999-9999-9999-9999-999999999999");
        expectReject(m);
    }

    // ---- entry: the P0 archive invariant ---------------------------------

    @Test
    void archiveExtensionTypeNeverAnEntry() {
        // CONTRACT P0 (Codex 019ec28a): archive is denied-aggregate, never an entry.
        Map<String, Object> m = validManifest();
        firstEntry(m).put("extension_type", "archive");
        expectReject(m);
    }

    @Test
    void archiveContainerExtensionTypeNeverAnEntry() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("extension_type", "archive_container");
        expectReject(m);
    }

    @Test
    void unknownExtensionTypeRejected() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("extension_type", "exe");
        expectReject(m);
    }

    @Test
    void invalidPathClassRejected() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("path_class", "personal/desktop");
        expectReject(m);
    }

    @Test
    void invalidMtimeBucketRejected() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("mtime_bucket", "P1D");
        expectReject(m);
    }

    @Test
    void invalidOwnerScopeRejected() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("owner_scope_marker", "personal");
        expectReject(m);
    }

    @Test
    void nonOpaqueRootRefRejected() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("root_ref", "managed_root:has space");
        expectReject(m);
    }

    @Test
    void negativeSizeRejected() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("size_bytes", -1);
        expectReject(m);
    }

    @Test
    void fileCountBelowOneRejected() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("file_count", 0);
        expectReject(m);
    }

    // ---- path-free (recursive) -------------------------------------------

    @Test
    void backslashPathAnywhereRejected() {
        Map<String, Object> m = validManifest();
        // a raw path stuffed into an otherwise-string field
        m.put("allowlist_profile_id", "C:\\Users\\Alice\\Documents");
        expectReject(m);
    }

    @Test
    void driveLetterPathRejected() {
        Map<String, Object> m = validManifest();
        m.put("allowlist_profile_id", "D:relative");
        expectReject(m);
    }

    @Test
    void dotdotTraversalRejected() {
        Map<String, Object> m = validManifest();
        m.put("allowlist_profile_id", "prof/../escape");
        expectReject(m);
    }

    @Test
    void scopeRootRefPathShapedRejected() {
        Map<String, Object> m = validManifest();
        @SuppressWarnings("unchecked")
        Map<String, Object> scope = (Map<String, Object>) m.get("scope");
        scope.put("managed_data_roots", new ArrayList<>(List.of("managed_root:abc/def")));
        expectReject(m);
    }

    // ---- aggregate invariants --------------------------------------------

    @Test
    void unknownDeniedClassRejected() {
        Map<String, Object> m = validManifest();
        agg(m).put("denied_count", 1);
        agg(m).put("denied_classes", new ArrayList<>(List.of("totally_made_up_class")));
        expectReject(m);
    }

    @Test
    void archiveContainerWithoutContainerCountRejected() {
        Map<String, Object> m = validManifest();
        agg(m).put("denied_count", 1);
        agg(m).put("denied_classes", new ArrayList<>(List.of("archive_container")));
        agg(m).put("container_count", 0); // violates archive_container ⟹ container_count≥1
        expectReject(m);
    }

    @Test
    void containerCountWithoutArchiveClassRejected() {
        Map<String, Object> m = validManifest();
        agg(m).put("denied_count", 1);
        agg(m).put("container_count", 1);
        agg(m).put("denied_classes", new ArrayList<>(List.of("private_key_material")));
        expectReject(m);
    }

    @Test
    void containerCountExceedingDeniedRejected() {
        Map<String, Object> m = validManifest();
        agg(m).put("denied_count", 1);
        agg(m).put("container_count", 2);
        agg(m).put("denied_classes", new ArrayList<>(List.of("archive_container")));
        expectReject(m);
    }

    @Test
    void totalEligibleCountMismatchRejected() {
        Map<String, Object> m = validManifest();
        agg(m).put("total_eligible_count", 5); // sum(entry.file_count) is 1
        expectReject(m);
    }

    @Test
    void negativeAggregateCountRejected() {
        Map<String, Object> m = validManifest();
        agg(m).put("unresolved_path_count", -1);
        expectReject(m);
    }

    @Test
    void validArchiveAggregatePasses() {
        // denied archive correctly reflected: denied_count=2, container_count=1,
        // archive_container present, container_count ≤ denied_count.
        Map<String, Object> m = validManifest();
        agg(m).put("denied_count", 2);
        agg(m).put("container_count", 1);
        agg(m).put("denied_classes", new ArrayList<>(List.of("mailbox_cache", "archive_container")));
        assertThatCode(() -> policy.validate(details(m), DEVICE, TENANT)).doesNotThrowAnyException();
    }
}
