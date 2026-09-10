package com.example.schema.catalog;

import com.example.schema.model.ForeignKeyInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the decisions that came out of measuring the live IFS dictionary
 * (gitops#3594). Each one is a place where the obvious implementation silently
 * returns less than the truth, so a regression here would not throw — it would
 * just quietly show an emptier schema.
 */
class OracleCatalogReaderTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final OracleCatalogReader reader = new OracleCatalogReader("ifs", jdbc, "IFSAPP");

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), anyMap(), any(RowCallbackHandler.class));
        return sql.getValue();
    }

    /**
     * extractTables issues two callback queries since gitops#3631 — the column query and the
     * ALL_TAB_COMMENTS pass. These helpers return the first (column) one, in order.
     */
    private String capturedColumnSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).query(sql.capture(), anyMap(), any(RowCallbackHandler.class));
        return sql.getAllValues().get(0);
    }

    private Map<String, Object> capturedColumnBinds() {
        ArgumentCaptor<Map<String, Object>> binds = ArgumentCaptor.forClass(Map.class);
        verify(jdbc, org.mockito.Mockito.times(2)).query(anyString(), binds.capture(), any(RowCallbackHandler.class));
        return binds.getAllValues().get(0);
    }

    @Test
    void identifiesItselfAsTheConfiguredOracleSource() {
        assertThat(reader.sourceId()).isEqualTo("ifs");
        assertThat(reader.engine()).isEqualTo("oracle");
    }

    @Test
    void ownerIsUpperCasedBecauseTheDictionaryStoresItThatWay() {
        reader.extractTables("ifsapp");

        // A lower-case owner would match nothing at all — the query would
        // succeed and return an empty schema.
        assertThat(capturedColumnBinds()).containsEntry("owner", "IFSAPP");
    }

    @Test
    void anAbsentSchemaFallsBackToTheConfiguredDefaultOwner() {
        reader.extractTables(null);

        assertThat(capturedColumnBinds()).containsEntry("owner", "IFSAPP");
    }

    @Test
    void objectQueryIncludesViewsNotJustTables() {
        reader.extractTables("IFSAPP");

        // The measured IFS instance holds 10,885 views and exactly one table (a
        // Toad artefact). Restricting this to TABLE would render the schema
        // explorer empty while every query still succeeded.
        assertThat(capturedColumnSql()).contains("'TABLE', 'VIEW'");
    }

    /**
     * gitops#3631 — the dictionary comment is where IFS keeps the column's label and key
     * flag; the measured snapshot had 205,874 columns and zero of either because it was
     * never read. Both passes must be there: column comments joined into the column query,
     * object comments in their own.
     */
    @Test
    void columnAndObjectCommentsAreRead() {
        reader.extractTables("IFSAPP");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).query(sql.capture(), anyMap(), any(RowCallbackHandler.class));
        assertThat(sql.getAllValues().get(0)).contains("ALL_COL_COMMENTS").contains("col_comment");
        assertThat(sql.getAllValues().get(1)).contains("ALL_TAB_COMMENTS");
    }

    @Test
    void viewSourceIsReadFromTheLongColumnNotTheTruncatingOne() {
        reader.getViewDefinitions("IFSAPP");

        String sql = capturedSql();
        // TEXT_VC caps at 4000 characters; 813 of the measured views exceed that
        // and the largest is 76,228 — using it would hand the relationship
        // parser silently truncated SQL.
        assertThat(sql).contains("TEXT");
        assertThat(sql).doesNotContain("TEXT_VC");
        // A JDBC LONG must be read before no other column of its row, so it has
        // to stay last in the projection.
        assertThat(sql.indexOf("VIEW_NAME")).isLessThan(sql.indexOf("TEXT"));
    }

    @Test
    void viewDefinitionsAreFetchedInOneQueryNotOnePerView() {
        reader.getViewDefinitions("IFSAPP");

        // Measured: the bulk read returns all 10,885 definitions in 1.5s, while
        // a per-view loop averaged 13ms each — 143s for identical output.
        verify(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    @Test
    void surfacesWithNoOracleEquivalentReportEmptyRatherThanGuessing() {
        // Oracle has no counterpart to the MSSQL change-tracking feature set or
        // to database options like recovery model and page verify. Returning
        // empty/null is the documented contract; inventing values would read as
        // measurement.
        assertThat(reader.extractChangeData("IFSAPP")).isEmpty();
        assertThat(reader.extractDatabaseOptions()).isNull();
    }

    @Test
    void uniqueConstraintQueryExcludesPrimaryKeys() {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());

        reader.extractUniqueConstraints("IFSAPP");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), anyMap());
        // UniqueConstraintType has no PRIMARY_KEY member: PK is carried by
        // ColumnInfo.pk(), and the MSSQL reader excludes it here too.
        assertThat(sql.getValue()).contains("CONSTRAINT_TYPE = 'U'");
        assertThat(sql.getValue()).doesNotContain("'P'");
    }

    @Test
    void tableNameListingCoversBothObjectClasses() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenReturn(List.of());

        reader.getTableNames("IFSAPP");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), anyMap(), eq(String.class));
        assertThat(sql.getValue()).contains("'TABLE', 'VIEW'");
    }

    /**
     * Feeds the two callback passes of extractForeignKeys: the REF= column pass with rows
     * {TABLE_NAME, COLUMN_NAME, COLUMN_ID, COMMENTS} and the LU= view-comment pass (the query
     * with {@code LIKE 'LU=%'}) with rows {TABLE_NAME, COMMENTS}.
     */
    private void stubColumnPass(List<Object[]> rows, List<Object[]> luRows) {
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            RowCallbackHandler handler = inv.getArgument(2);
            if (sql.contains("LIKE 'LU=%'")) {
                for (Object[] r : luRows) {
                    ResultSet rs = mock(ResultSet.class);
                    org.mockito.Mockito.when(rs.getString("TABLE_NAME")).thenReturn((String) r[0]);
                    org.mockito.Mockito.when(rs.getString("COMMENTS")).thenReturn((String) r[1]);
                    handler.processRow(rs);
                }
                return null;
            }
            for (Object[] r : rows) {
                ResultSet rs = mock(ResultSet.class);
                org.mockito.Mockito.when(rs.getString("TABLE_NAME")).thenReturn((String) r[0]);
                org.mockito.Mockito.when(rs.getString("COLUMN_NAME")).thenReturn((String) r[1]);
                org.mockito.Mockito.when(rs.getInt("COLUMN_ID")).thenReturn((Integer) r[2]);
                org.mockito.Mockito.when(rs.getString("COMMENTS")).thenReturn((String) r[3]);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    private static Map<String, Object> declaredFkRow(String name, String fromTable, String fromColumn,
                                                     String toTable, String toColumn) {
        Map<String, Object> row = new HashMap<>();
        row.put("FK_NAME", name);
        row.put("FROM_OWNER", "IFSAPP");
        row.put("FROM_TABLE", fromTable);
        row.put("FROM_COLUMN", fromColumn);
        row.put("TO_OWNER", "IFSAPP");
        row.put("TO_TABLE", toTable);
        row.put("TO_COLUMN", toColumn);
        row.put("STATUS", "ENABLED");
        row.put("VALIDATED", "VALIDATED");
        row.put("DELETE_RULE", "NO ACTION");
        row.put("POSITION", 1);
        return row;
    }

    /**
     * gitops#3631 slice 2: the account's ALL_CONSTRAINTS answers nothing (measured: 0 keys),
     * so the REF= pass over the views' column comments supplies the keys — restricted to
     * views (a REF names a logical unit; a table can never be its target), and merged with
     * declared constraints on identity rather than name (Codex 01a08afc P2).
     */
    @Test
    void foreignKeysComeFromViewCommentsAndMergeWithDeclaredOnesByIdentity() {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(
            declaredFkRow("FK_ABSENCE_SITE", "ABSENCE_REGISTRATION", "CONTRACT", "SITE", "CONTRACT")));
        stubColumnPass(List.of(
            new Object[] {"SITE", "CONTRACT", 1, "FLAGS=KMI-L^PROMPT=Site^"},
            new Object[] {"COMPANY", "COMPANY", 1, "FLAGS=KMI-L^PROMPT=Company^"},
            new Object[] {"COMPANY_PERSON", "COMPANY", 1, "FLAGS=PMI--^REF=Company^"},
            new Object[] {"COMPANY_PERSON", "EMP_NO", 2, "FLAGS=KMI-L^"},
            new Object[] {"ABSENCE_REGISTRATION", "COMPANY", 1, "FLAGS=PMI--^REF=Company^"},
            new Object[] {"ABSENCE_REGISTRATION", "EMP_NO", 2, "FLAGS=PMI--^REF=CompanyPerson(company)/NOCHECK^"},
            new Object[] {"ABSENCE_REGISTRATION", "ABSENCE_ID", 3, "FLAGS=KMI-L^"},
            new Object[] {"ABSENCE_REGISTRATION", "CONTRACT", 4, "FLAGS=A-IU-^REF=Site^"},
            new Object[] {"ABSENCE_REGISTRATION", "NOTE", 5, null},
            // gitops#3643: the LU DeliveryNote has no DELIVERY_NOTE view here; its JOIN view declares the LU.
            new Object[] {"DELIVERY_NOTE_JOIN", "DELNOTE_NO", 1, "FLAGS=KMI-L^"},
            new Object[] {"ABSENCE_REGISTRATION", "DELNOTE_NO", 6, "FLAGS=A-IU-^REF=DeliveryNote^"},
            // EngPartMaster: two visible views declare it; only ENG_PART_MASTER_MAIN's comment carries TABLE=<itself>_TAB.
            new Object[] {"ENG_PART_MASTER_MAIN", "PART_NO", 1, "FLAGS=KMI-L^"},
            new Object[] {"ENG_PART_MASTER_ALT_LOV", "PART_NO", 1, "FLAGS=KMI-L^"},
            new Object[] {"ABSENCE_REGISTRATION", "PART_NO", 7, "FLAGS=A-IU-^REF=EngPartMaster^"}),
            List.of(
            new Object[] {"DELIVERY_NOTE_JOIN", "LU=DeliveryNote^PROMPT=Delivery Note^MODULE=ORDER^"},
            new Object[] {"ENG_PART_MASTER_ALT_LOV", "LU=EngPartMaster^PROMPT=Eng Part Master^MODULE=PDMCON^TABLE=ENG_PART_MASTER_TAB^"},
            new Object[] {"ENG_PART_MASTER_MAIN", "LU=EngPartMaster^PROMPT=Eng Part Master^MODULE=PDMCON^TABLE=ENG_PART_MASTER_MAIN_TAB^"},
            new Object[] {"COMPANY", "LU=Company^PROMPT=Company^MODULE=ENTERP^TABLE=COMPANY_TAB^"}));

        List<ForeignKeyInfo> keys = reader.extractForeignKeys("IFSAPP");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).query(sql.capture(), anyMap(), any(RowCallbackHandler.class));
        assertThat(sql.getAllValues().get(0)).contains("JOIN ALL_VIEWS");
        assertThat(sql.getAllValues().get(0)).contains("ORDER BY c.TABLE_NAME, c.COLUMN_ID");
        assertThat(sql.getAllValues().get(1)).contains("ALL_TAB_COMMENTS").contains("JOIN ALL_VIEWS").contains("LIKE 'LU=%'");

        Map<String, ForeignKeyInfo> byName = new HashMap<>();
        keys.forEach(fk -> byName.put(fk.name(), fk));
        // The declared constraint states the same reference as ABSENCE_REGISTRATION.CONTRACT's REF=Site: one key, the declared one.
        assertThat(byName).containsKeys("FK_ABSENCE_SITE", "IFS_REF_COMPANY_PERSON.COMPANY",
            "IFS_REF_ABSENCE_REGISTRATION.COMPANY", "IFS_REF_ABSENCE_REGISTRATION.EMP_NO",
            "IFS_REF_ABSENCE_REGISTRATION.DELNOTE_NO");
        assertThat(byName.get("IFS_REF_ABSENCE_REGISTRATION.DELNOTE_NO").toTable()).as("resolved through the LU= index").isEqualTo("DELIVERY_NOTE_JOIN");
        assertThat(byName.get("IFS_REF_ABSENCE_REGISTRATION.PART_NO").toTable())
            .as("the base view is the one whose own TABLE entry is <VIEW>_TAB, read from the comment").isEqualTo("ENG_PART_MASTER_MAIN");
        assertThat(byName).doesNotContainKey("IFS_REF_ABSENCE_REGISTRATION.CONTRACT");
        assertThat(keys).hasSize(6);
        assertThat(byName.get("FK_ABSENCE_SITE").isNotTrusted()).isFalse();

        ForeignKeyInfo composite = byName.get("IFS_REF_ABSENCE_REGISTRATION.EMP_NO");
        assertThat(composite.fromColumns()).containsExactly("COMPANY", "EMP_NO");
        assertThat(composite.toColumns()).containsExactly("COMPANY", "EMP_NO");
        assertThat(composite.toTable()).isEqualTo("COMPANY_PERSON");
        assertThat(composite.isNotTrusted()).isTrue();
        assertThat(composite.fromSchema()).isEqualTo("IFSAPP");
    }
}
