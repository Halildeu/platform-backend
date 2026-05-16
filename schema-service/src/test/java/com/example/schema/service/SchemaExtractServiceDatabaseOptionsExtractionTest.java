package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.DatabaseOptionsInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phase B1-8 (capability M15 — Codex 019e32bc): {@code extractDatabaseOptions}
 * tests. The mocked {@code jdbc.query} is invoked twice — the {@code sys.databases}
 * option row then the {@code sys.database_files} size aggregate — so the stub
 * branches on the SQL string. Locks the column → {@link DatabaseOptionsInfo}
 * field mapping, the {@code null}-on-empty contract, and the SQL shape
 * ({@code DB_ID()} binding, file-size aggregate).
 */
class SchemaExtractServiceDatabaseOptionsExtractionTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SchemaExtractService service = new SchemaExtractService(jdbc);

    private String capturedOptionsSql;
    private String capturedFilesSql;

    private void stub(ResultSet optionsRow, ResultSet filesRow) {
        // jdbc.query is called twice; branch on the SQL string.
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            RowCallbackHandler handler = inv.getArgument(2);
            if (sql.contains("sys.database_files")) {
                capturedFilesSql = sql;
                if (filesRow != null) {
                    handler.processRow(filesRow);
                }
            } else {
                capturedOptionsSql = sql;
                if (optionsRow != null) {
                    handler.processRow(optionsRow);
                }
            }
            return null;
        }).when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    /** A fully stubbed {@code sys.databases} option row — distinct true/false
     *  mix so a mis-wired column flips an assertion. */
    private static ResultSet optionsRow() throws SQLException {
        ResultSet r = mock(ResultSet.class);
        when(r.getString("database_name")).thenReturn("workcube");
        when(r.getString("collation_name")).thenReturn("SQL_Latin1_General_CP1_CI_AS");
        when(r.getInt("compatibility_level")).thenReturn(150);
        when(r.getString("recovery_model_desc")).thenReturn("SIMPLE");
        when(r.getBoolean("is_read_committed_snapshot_on")).thenReturn(true);
        when(r.getString("snapshot_isolation_state_desc")).thenReturn("ON");
        when(r.getString("page_verify_option_desc")).thenReturn("CHECKSUM");
        when(r.getBoolean("is_auto_create_stats_on")).thenReturn(true);
        when(r.getBoolean("is_auto_update_stats_on")).thenReturn(true);
        when(r.getBoolean("is_auto_update_stats_async_on")).thenReturn(false);
        when(r.getBoolean("is_auto_shrink_on")).thenReturn(false);
        when(r.getBoolean("is_auto_close_on")).thenReturn(false);
        when(r.getBoolean("is_ansi_nulls_on")).thenReturn(true);
        when(r.getBoolean("is_ansi_padding_on")).thenReturn(true);
        when(r.getBoolean("is_ansi_warnings_on")).thenReturn(true);
        when(r.getBoolean("is_ansi_null_default_on")).thenReturn(false);
        when(r.getBoolean("is_arithabort_on")).thenReturn(true);
        when(r.getBoolean("is_quoted_identifier_on")).thenReturn(true);
        when(r.getBoolean("is_concat_null_yields_null_on")).thenReturn(false);
        when(r.getBoolean("is_numeric_roundabort_on")).thenReturn(false);
        return r;
    }

    private static ResultSet filesRow(int dataCount, int logCount, long dataKb, long logKb)
            throws SQLException {
        ResultSet r = mock(ResultSet.class);
        when(r.getInt("data_file_count")).thenReturn(dataCount);
        when(r.getInt("log_file_count")).thenReturn(logCount);
        when(r.getLong("data_file_size_kb")).thenReturn(dataKb);
        when(r.getLong("log_file_size_kb")).thenReturn(logKb);
        return r;
    }

    @Test
    void allOptionAndFileFields_mapped() throws SQLException {
        stub(optionsRow(), filesRow(3, 1, 5_242_880L, 1_048_576L));

        DatabaseOptionsInfo db = service.extractDatabaseOptions();

        assertThat(db).isNotNull();
        assertThat(db.databaseName()).isEqualTo("workcube");
        assertThat(db.collation()).isEqualTo("SQL_Latin1_General_CP1_CI_AS");
        assertThat(db.compatibilityLevel()).isEqualTo(150);
        assertThat(db.recoveryModel()).isEqualTo("SIMPLE");
        assertThat(db.readCommittedSnapshotEnabled()).isTrue();
        assertThat(db.snapshotIsolationState()).isEqualTo("ON");
        assertThat(db.pageVerifyOption()).isEqualTo("CHECKSUM");
        assertThat(db.autoCreateStatisticsEnabled()).isTrue();
        assertThat(db.autoUpdateStatisticsEnabled()).isTrue();
        assertThat(db.autoUpdateStatisticsAsyncEnabled()).isFalse();
        assertThat(db.autoShrinkEnabled()).isFalse();
        assertThat(db.autoCloseEnabled()).isFalse();
        assertThat(db.ansiNullsEnabled()).isTrue();
        assertThat(db.ansiPaddingEnabled()).isTrue();
        assertThat(db.ansiWarningsEnabled()).isTrue();
        assertThat(db.ansiNullDefaultEnabled()).isFalse();
        assertThat(db.arithAbortEnabled()).isTrue();
        assertThat(db.quotedIdentifierEnabled()).isTrue();
        assertThat(db.concatNullYieldsNull()).isFalse();
        assertThat(db.numericRoundAbortEnabled()).isFalse();
        assertThat(db.dataFileCount()).isEqualTo(3);
        assertThat(db.logFileCount()).isEqualTo(1);
        assertThat(db.dataFileSizeKb()).isEqualTo(5_242_880L);
        assertThat(db.logFileSizeKb()).isEqualTo(1_048_576L);
    }

    @Test
    void noDatabaseRow_yieldsNull() throws SQLException {
        // sys.databases returns no row → extraction returns null, not throws.
        stub(null, null);

        assertThat(service.extractDatabaseOptions()).isNull();
    }

    @Test
    void extractDatabaseOptions_optionsSqlBindsToCurrentDatabase() throws SQLException {
        stub(optionsRow(), filesRow(1, 1, 100L, 50L));
        service.extractDatabaseOptions();

        assertThat(capturedOptionsSql)
                .contains("sys.databases")
                .contains("DB_ID()")
                .contains("recovery_model_desc")
                .contains("is_read_committed_snapshot_on");
    }

    @Test
    void extractDatabaseOptions_filesSqlAggregatesDatabaseFiles() throws SQLException {
        stub(optionsRow(), filesRow(1, 1, 100L, 50L));
        service.extractDatabaseOptions();

        assertThat(capturedFilesSql)
                .contains("sys.database_files")
                .contains("CAST(size AS bigint)");
    }
}
