package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.StorageInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phase B1-6 (capability M6 — Codex 019e329a): {@code extractStorage} row
 * mapping + SQL-shape tests. The mocked {@code jdbc.query} replays per-table
 * aggregate rows through the {@code RowCallbackHandler}, locking the column →
 * {@link StorageInfo} field mapping. A separate group captures the SQL string
 * and asserts the {@code sys.dm_db_partition_stats} aggregation, the
 * base-index ({@code index_id IN (0,1)}) scoping of {@code row_count} /
 * {@code data_kb}, and the distinct LOB / row-overflow decomposition.
 */
class SchemaExtractServiceStorageExtractionTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SchemaExtractService service = new SchemaExtractService(jdbc);

    private String capturedSql;

    private void stubQuery(List<ResultSet> rows) {
        // jdbc.query(sql, params, RowCallbackHandler) is void → doAnswer.
        doAnswer(inv -> {
            capturedSql = inv.getArgument(0);
            RowCallbackHandler handler = inv.getArgument(2);
            for (ResultSet row : rows) {
                handler.processRow(row);
            }
            return null;
        }).when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    /** Fluent builder for one per-table storage aggregate row. */
    private static final class Row {
        private String table = "INVOICE";
        private long rowCount = 1_000L;
        private long reservedKb = 800L;
        private long usedKb = 700L;
        private long dataKb = 400L;
        private long indexKb = 200L;
        private long lobKb = 80L;
        private long rowOverflowKb = 20L;

        Row table(String v) { this.table = v; return this; }
        Row rowCount(long v) { this.rowCount = v; return this; }
        Row reservedKb(long v) { this.reservedKb = v; return this; }
        Row usedKb(long v) { this.usedKb = v; return this; }
        Row dataKb(long v) { this.dataKb = v; return this; }
        Row indexKb(long v) { this.indexKb = v; return this; }
        Row lobKb(long v) { this.lobKb = v; return this; }
        Row rowOverflowKb(long v) { this.rowOverflowKb = v; return this; }

        ResultSet build() throws SQLException {
            ResultSet r = mock(ResultSet.class);
            when(r.getString("table_name")).thenReturn(table);
            when(r.getString("schema_name")).thenReturn("dbo");
            when(r.getLong("row_count")).thenReturn(rowCount);
            when(r.getLong("reserved_kb")).thenReturn(reservedKb);
            when(r.getLong("used_kb")).thenReturn(usedKb);
            when(r.getLong("data_kb")).thenReturn(dataKb);
            when(r.getLong("index_kb")).thenReturn(indexKb);
            when(r.getLong("lob_kb")).thenReturn(lobKb);
            when(r.getLong("row_overflow_kb")).thenReturn(rowOverflowKb);
            return r;
        }
    }

    private List<StorageInfo> extract(Row... rows) throws SQLException {
        List<ResultSet> rs = new ArrayList<>();
        for (Row row : rows) {
            rs.add(row.build());
        }
        stubQuery(rs);
        return service.extractStorage("workcube_mikrolink");
    }

    @Test
    void singleTable_allFieldsMapped() throws SQLException {
        StorageInfo s = extract(new Row().table("INVOICE")
                .rowCount(12_500L).reservedKb(4_096L).usedKb(3_800L)
                .dataKb(2_400L).indexKb(1_000L).lobKb(300L).rowOverflowKb(100L)).get(0);

        assertThat(s.table()).isEqualTo("INVOICE");
        assertThat(s.schema()).isEqualTo("dbo");
        assertThat(s.rowCount()).isEqualTo(12_500L);
        assertThat(s.reservedKb()).isEqualTo(4_096L);
        assertThat(s.usedKb()).isEqualTo(3_800L);
        assertThat(s.dataKb()).isEqualTo(2_400L);
        assertThat(s.indexKb()).isEqualTo(1_000L);
        assertThat(s.lobKb()).isEqualTo(300L);
        assertThat(s.rowOverflowKb()).isEqualTo(100L);
    }

    @Test
    void usedDecomposition_dataIndexLobOverflowSumToUsed() throws SQLException {
        // Mirrors the SQL invariant dataKb + indexKb + lobKb + rowOverflowKb
        // == usedKb (the extraction derives indexKb as the remainder).
        StorageInfo s = extract(new Row().usedKb(700L)
                .dataKb(400L).indexKb(200L).lobKb(80L).rowOverflowKb(20L)).get(0);

        assertThat(s.dataKb() + s.indexKb() + s.lobKb() + s.rowOverflowKb())
                .isEqualTo(s.usedKb());
    }

    @Test
    void multipleTables_eachYieldsStorageInfo() throws SQLException {
        List<StorageInfo> storage = extract(
                new Row().table("INVOICE"),
                new Row().table("ORDERS"));

        assertThat(storage).extracting(StorageInfo::table)
                .containsExactlyInAnyOrder("INVOICE", "ORDERS");
    }

    @Test
    void emptySchema_yieldsNoStorage() throws SQLException {
        stubQuery(List.of());
        assertThat(service.extractStorage("workcube_mikrolink")).isEmpty();
    }

    // --- SQL shape (Codex 019e329a: mocked rows cannot prove the aggregation
    //     — assert the SQL string directly) ---

    @Test
    void extractStorage_sqlAggregatesPartitionStatsPerTable() throws SQLException {
        extract(new Row());

        assertThat(capturedSql)
                .contains("sys.dm_db_partition_stats")
                .contains("GROUP BY t.name, sch.name")
                .contains("SUM(ps.reserved_page_count)");
    }

    @Test
    void extractStorage_sqlScopesBaseIndexAndSeparatesLobOverflow() throws SQLException {
        extract(new Row());

        assertThat(capturedSql)
                .contains("ps.index_id IN (0, 1)")
                .contains("ps.lob_used_page_count")
                .contains("ps.row_overflow_used_page_count");
    }
}
