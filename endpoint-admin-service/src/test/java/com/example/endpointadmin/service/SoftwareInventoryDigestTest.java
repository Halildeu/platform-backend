package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.SoftwareInstallSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-024 — pure unit tests for {@link SoftwareInventoryDigest}.
 *
 * <p>The appKey + digest hash are the diff identity, so determinism +
 * normalization correctness are load-bearing: ingest builds them once and
 * the diff service compares them later; any drift between the two would
 * silently corrupt every diff.
 */
class SoftwareInventoryDigestTest {

    @Test
    void appKeyIsStableForSameNaturalKey() {
        String a = SoftwareInventoryDigest.appKey("7-Zip", "Igor Pavlov", null);
        String b = SoftwareInventoryDigest.appKey("7-Zip", "Igor Pavlov", null);
        assertThat(a).isEqualTo(b);
        assertThat(a).matches("^[a-f0-9]{64}$");
    }

    @Test
    void appKeyIsCaseAndWhitespaceInsensitive() {
        String a = SoftwareInventoryDigest.appKey("7-Zip", "Igor Pavlov", null);
        String b = SoftwareInventoryDigest.appKey("  7-zip  ", "IGOR PAVLOV", null);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void appKeyDiffersWhenPublisherDiffers() {
        String a = SoftwareInventoryDigest.appKey("Updater", "VendorA", null);
        String b = SoftwareInventoryDigest.appKey("Updater", "VendorB", null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void appKeyDiffersWhenPublisherPresentVsAbsent() {
        String withPublisher = SoftwareInventoryDigest.appKey("App", "Vendor", null);
        String withoutPublisher = SoftwareInventoryDigest.appKey("App", null, null);
        assertThat(withPublisher).isNotEqualTo(withoutPublisher);
    }

    @Test
    void appKeyIncludesMsiHashWhenPresent() {
        String noHash = SoftwareInventoryDigest.appKey("App", "Vendor", null);
        String withHash = SoftwareInventoryDigest.appKey(
                "App", "Vendor", "sha256:0123456789abcdef");
        assertThat(noHash).isNotEqualTo(withHash);
    }

    @Test
    void digestHashIsOrderIndependent() {
        List<Map<String, Object>> ascending = SoftwareInventoryDigest.fromItems(List.of(
                item("7-Zip", "24.07", "Igor Pavlov"),
                item("Notepad++", "8.6", "Don Ho")));
        List<Map<String, Object>> descending = SoftwareInventoryDigest.fromItems(List.of(
                item("Notepad++", "8.6", "Don Ho"),
                item("7-Zip", "24.07", "Igor Pavlov")));
        assertThat(SoftwareInventoryDigest.digestHash(ascending))
                .isEqualTo(SoftwareInventoryDigest.digestHash(descending));
    }

    @Test
    void digestHashChangesWhenVersionChanges() {
        List<Map<String, Object>> before = SoftwareInventoryDigest.fromItems(List.of(
                item("7-Zip", "24.07", "Igor Pavlov")));
        List<Map<String, Object>> after = SoftwareInventoryDigest.fromItems(List.of(
                item("7-Zip", "24.08", "Igor Pavlov")));
        assertThat(SoftwareInventoryDigest.digestHash(before))
                .isNotEqualTo(SoftwareInventoryDigest.digestHash(after));
    }

    @Test
    void emptyDigestHashIsStable() {
        assertThat(SoftwareInventoryDigest.digestHash(List.of()))
                .isEqualTo(SoftwareInventoryDigest.digestHash(List.of()))
                .matches("^[a-f0-9]{64}$");
    }

    @Test
    void fromItemsProjectsOnlyWhitelistFields() {
        List<Map<String, Object>> digest = SoftwareInventoryDigest.fromItems(List.of(
                item("7-Zip", "24.07", "Igor Pavlov")));
        assertThat(digest).hasSize(1);
        Map<String, Object> entry = digest.get(0);
        assertThat(entry.keySet()).containsExactlyInAnyOrder(
                SoftwareInventoryDigest.KEY_APP_KEY,
                SoftwareInventoryDigest.KEY_DISPLAY_NAME,
                SoftwareInventoryDigest.KEY_PUBLISHER,
                SoftwareInventoryDigest.KEY_VERSION,
                SoftwareInventoryDigest.KEY_MSI_HASH);
        assertThat(entry).containsEntry(
                SoftwareInventoryDigest.KEY_DISPLAY_NAME, "7-Zip");
        assertThat(entry).containsEntry(
                SoftwareInventoryDigest.KEY_VERSION, "24.07");
    }

    private static EndpointSoftwareInventoryItem item(
            String name, String version, String publisher) {
        EndpointSoftwareInventoryItem item = new EndpointSoftwareInventoryItem();
        item.setDisplayName(name);
        item.setDisplayVersion(version);
        item.setPublisher(publisher);
        item.setInstallSource(SoftwareInstallSource.HKLM);
        return item;
    }
}
