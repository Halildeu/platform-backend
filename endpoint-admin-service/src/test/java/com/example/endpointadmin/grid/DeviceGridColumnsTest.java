package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.grid.DeviceGridColumns.ColumnType;
import com.example.endpointadmin.grid.DeviceGridColumns.GridColumn;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * WEB-015 v2-a (Codex 019e8785 iter-2) — DeviceGridColumns registry tests.
 *
 * <p>Pinned invariants:
 * <ul>
 *   <li>{@link DeviceGridColumns#SCHEMA_VERSION} = 3 (bump from 2).</li>
 *   <li>Registry carries the 5 new BE-025 + AG-041 column ids in the
 *       expected canonical (raw-export) order, after the existing
 *       AG-036 columns.</li>
 *   <li>SQL expressions reference the {@code pe} and {@code ac}
 *       LATERAL aliases (not interpolated client input — the registry
 *       is the security spine).</li>
 *   <li>JSONB-safe array length expression is the canonical
 *       {@code jsonb_typeof = 'array'} guard form.</li>
 *   <li>{@link DeviceGridColumns#columnIdsHash()} is stable, lowercase
 *       hex SHA-256 over the comma-joined column ids.</li>
 * </ul>
 */
class DeviceGridColumnsTest {

    private static final List<String> EXPECTED_NEW_COL_IDS = List.of(
            "prohibited_status",
            "prohibited_decision",
            "prohibited_findings_count",
            "app_control_wdac_mode",
            "app_control_app_id_svc_state");

    @Test
    void schemaVersionIsThree() {
        assertThat(DeviceGridColumns.SCHEMA_VERSION).isEqualTo(3);
    }

    @Test
    void registryAppendsFiveNewColumnsAfterOutdatedColumns() {
        List<String> ids = DeviceGridColumns.allColumnIds();
        int outdatedCollectedAtIdx = ids.indexOf("outdated_collected_at");
        assertThat(outdatedCollectedAtIdx).isPositive();
        assertThat(ids.subList(outdatedCollectedAtIdx + 1, outdatedCollectedAtIdx + 1 + 5))
                .containsExactlyElementsOf(EXPECTED_NEW_COL_IDS);
    }

    @Test
    void prohibitedStatusUsesCaseExpressionOverPeId() {
        GridColumn c = DeviceGridColumns.byId("prohibited_status");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.ENUM);
        assertThat(c.sqlExpr())
                .contains("CASE WHEN pe.id IS NULL THEN 'NO_EVALUATION' ELSE 'OK' END");
    }

    @Test
    void prohibitedDecisionReadsPersistedDecision() {
        GridColumn c = DeviceGridColumns.byId("prohibited_decision");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.ENUM);
        assertThat(c.sqlExpr()).isEqualTo("pe.decision");
    }

    @Test
    void prohibitedFindingsCountUsesJsonbDefensiveArrayLength() {
        GridColumn c = DeviceGridColumns.byId("prohibited_findings_count");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.NUMBER);
        assertThat(c.sqlExpr())
                .contains("jsonb_typeof(pe.evidence #> '{matchedItems,prohibitedInstalled}') = 'array'")
                .contains("jsonb_array_length(pe.evidence #> '{matchedItems,prohibitedInstalled}')")
                .contains("WHEN pe.id IS NULL THEN NULL");
    }

    @Test
    void appControlColumnsReadFromAcAliasAndLeaveNullPathOnNoSnapshot() {
        GridColumn wdac = DeviceGridColumns.byId("app_control_wdac_mode");
        assertThat(wdac).isNotNull();
        assertThat(wdac.type()).isEqualTo(ColumnType.ENUM);
        assertThat(wdac.sqlExpr()).isEqualTo("ac.wdac_mode");

        GridColumn svc = DeviceGridColumns.byId("app_control_app_id_svc_state");
        assertThat(svc).isNotNull();
        assertThat(svc.sqlExpr()).isEqualTo("ac.app_locker_app_id_svc_state");
    }

    @Test
    void columnIdsHashIsLowercaseHexSha256AndStable() {
        String h1 = DeviceGridColumns.columnIdsHash();
        String h2 = DeviceGridColumns.columnIdsHash();
        assertThat(h1).matches("^[0-9a-f]{64}$");
        assertThat(h2).isEqualTo(h1);
    }

    @Test
    void columnIdsHashMatchesCanonicalCommaJoinedIds() {
        String expected = sha256Hex(String.join(",", DeviceGridColumns.allColumnIds()));
        assertThat(DeviceGridColumns.columnIdsHash()).isEqualTo(expected);
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
