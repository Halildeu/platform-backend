package com.example.schema.catalog;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void identifiesItselfAsTheConfiguredOracleSource() {
        assertThat(reader.sourceId()).isEqualTo("ifs");
        assertThat(reader.engine()).isEqualTo("oracle");
    }

    @Test
    void ownerIsUpperCasedBecauseTheDictionaryStoresItThatWay() {
        reader.extractTables("ifsapp");

        ArgumentCaptor<Map<String, Object>> binds = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).query(anyString(), binds.capture(), any(RowCallbackHandler.class));
        // A lower-case owner would match nothing at all — the query would
        // succeed and return an empty schema.
        assertThat(binds.getValue()).containsEntry("owner", "IFSAPP");
    }

    @Test
    void anAbsentSchemaFallsBackToTheConfiguredDefaultOwner() {
        reader.extractTables(null);

        ArgumentCaptor<Map<String, Object>> binds = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).query(anyString(), binds.capture(), any(RowCallbackHandler.class));
        assertThat(binds.getValue()).containsEntry("owner", "IFSAPP");
    }

    @Test
    void objectQueryIncludesViewsNotJustTables() {
        reader.extractTables("IFSAPP");

        // The measured IFS instance holds 10,885 views and exactly one table (a
        // Toad artefact). Restricting this to TABLE would render the schema
        // explorer empty while every query still succeeded.
        assertThat(capturedSql()).contains("'TABLE', 'VIEW'");
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
}
