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
 * (contract §5). No Spring / no Postgres. Covers strict-schema (Codex 019ec2e6
 * P0: exact keys, no unknown fields, key+value path-free, integral numbers,
 * null-details, full-envelope incl summary/errorCode/errorMessage, embedded
 * drive paths) + every contract invariant. The wired 400 reject path is
 * covered in EndpointAgentCommandServiceTest.
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
        m.put("generated_at", "2026-06-14T21:00:00Z");
        m.put("allowlist_profile_id", "prof-1");
        m.put("scope", scope);
        m.put("entries", entries);
        m.put("aggregate", agg);
        return m;
    }

    private Map<String, Object> details(Object manifest) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> scope(Map<String, Object> manifest) {
        return (Map<String, Object>) manifest.get("scope");
    }

    // Localized call (signature change → one place).
    private void run(Map<String, Object> details, String summary, String errorCode, String errorMessage) {
        policy.validate(details, summary, errorCode, errorMessage, DEVICE, TENANT);
    }

    private void expectReject(Map<String, Object> manifest) {
        assertThatThrownBy(() -> run(details(manifest), "done", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("BACKUP_DRYRUN_MANIFEST_VIOLATION");
    }

    // ---- happy path -------------------------------------------------------

    @Test
    void validManifestPasses() {
        assertThatCode(() -> run(details(validManifest()), "done", null, null)).doesNotThrowAnyException();
    }

    @Test
    void nullBindingSkipsDeviceTenantCheck() {
        assertThatCode(() -> policy.validate(details(validManifest()), "done", null, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void validArchiveAggregatePasses() {
        Map<String, Object> m = validManifest();
        agg(m).put("denied_count", 2);
        agg(m).put("container_count", 1);
        agg(m).put("denied_classes", new ArrayList<>(List.of("mailbox_cache", "archive_container")));
        assertThatCode(() -> run(details(m), "done", null, null)).doesNotThrowAnyException();
    }

    // ---- P0: strict schema / full envelope (Codex 019ec2e6) ---------------

    @Test
    void nullDetailsRejected() {
        assertThatThrownBy(() -> run(null, "done", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void siblingDetailsKeyRejected() {
        Map<String, Object> d = details(validManifest());
        d.put("log", "harmless-looking");
        assertThatThrownBy(() -> run(d, "done", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownManifestFieldRejected() {
        Map<String, Object> m = validManifest();
        m.put("content_sha256", "deadbeef");
        expectReject(m);
    }

    @Test
    void unknownEntryFieldRejected() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("rawPath", "something");
        expectReject(m);
    }

    @Test
    void pathShapedMapKeyRejected() {
        Map<String, Object> m = validManifest();
        scope(m).put("C:\\Users\\Alice", true);
        expectReject(m);
    }

    @Test
    void decimalNumberRejected() {
        Map<String, Object> m = validManifest();
        firstEntry(m).put("size_bytes", 1.9);
        expectReject(m);
    }

    @Test
    void missingRequiredFieldRejected() {
        Map<String, Object> m = validManifest();
        m.remove("allowlist_profile_id");
        expectReject(m);
    }

    @Test
    void missingGeneratedAtRejected() {
        // generated_at is a contract v1 top-level field (Codex 019ec2e6 P1).
        Map<String, Object> m = validManifest();
        m.remove("generated_at");
        expectReject(m);
    }

    @Test
    void summaryWithBackslashPathRejected() {
        assertThatThrownBy(() -> run(details(validManifest()), "scanned C:\\Users\\Alice\\Documents", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summaryWithEmbeddedForwardSlashDrivePathRejected() {
        // Codex 019ec2e6 P0#2: embedded "C:/..." (mid-string, forward slash).
        assertThatThrownBy(() -> run(details(validManifest()), "failed at C:/Users/Alice/Documents", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summaryWithDriveRelativePathRejected() {
        assertThatThrownBy(() -> run(details(validManifest()), "wrote to D:report", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorCodeWithRawPathRejected() {
        // Codex 019ec2e6 P0#1: errorCode persists too (endpoint_command_results).
        assertThatThrownBy(() -> run(details(validManifest()), "done", "C:\\boom", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorMessageWithUncPathRejected() {
        assertThatThrownBy(() -> run(details(validManifest()), "done", null, "failed at \\\\server\\share\\hr"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonObjectManifestRejected() {
        assertThatThrownBy(() -> run(details("not-a-map"), "done", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- header / binding -------------------------------------------------

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
        agg(m).put("total_eligible_count", 0);
        expectReject(m);
    }

    // ---- scope / path-free ------------------------------------------------

    @Test
    void scopeRootRefPathShapedRejected() {
        Map<String, Object> m = validManifest();
        scope(m).put("managed_data_roots", new ArrayList<>(List.of("managed_root:abc/def")));
        expectReject(m);
    }

    @Test
    void backslashValueAnywhereRejected() {
        Map<String, Object> m = validManifest();
        m.put("allowlist_profile_id", "C:\\Users\\Alice\\Documents");
        expectReject(m);
    }

    @Test
    void dotdotTraversalRejected() {
        Map<String, Object> m = validManifest();
        m.put("allowlist_profile_id", "prof/../escape");
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
        agg(m).put("container_count", 0);
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
        agg(m).put("total_eligible_count", 5);
        expectReject(m);
    }

    @Test
    void negativeAggregateCountRejected() {
        Map<String, Object> m = validManifest();
        agg(m).put("unresolved_path_count", -1);
        expectReject(m);
    }
}
