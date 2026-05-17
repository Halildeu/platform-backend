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
 * Phase B1-6 (capability M6 — Codex 019e329a / 019e34f9): {@code extractStorage}
 * row mapping + SQL-shape tests.
 *
 * <p>Q4 rewrite: storage now reads the {@code sys.partitions} +
 * {@code sys.allocation_units} CATALOG views instead of the
 * {@code sys.dm_db_partition_stats} DMV — catalog views need no
 * {@code VIEW DATABASE [PERFORMANCE] STATE} grant. The mocked {@code jdbc.query}
 * replays per-table aggregate rows; {@code index_kb} is no longer a SQL column
 * — the extraction derives it as the clamped remainder
 * {@code max(0, usedKb - dataKb - lobKb - rowOverflowKb)}. A separate group
 * captures the SQL string and asserts the catalog-view sourcing, the
 * fan-out-safe {@code part_rows} CTE and the {@code au.type} decomposition.
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

    /**
     * Fluent builder for one per-table storage aggregate row. {@code indexKb}
     * is NOT a column — the extraction computes it from the other four size
     * fields, so the builder only carries the SQL-sourced values.
     */
    private static final class Row {
        private String table = "INVOICE";
        private long rowCount = 1_000L;
        private long reservedKb = 800L;
        private long usedKb = 700L;
        private long dataKb = 400L;
        private long lobKb = 80L;
        private long rowOverflowKb = 20L;

        Row table(String v) { this.table = v; return this; }
        Row rowCount(long v) { this.rowCount = v; return this; }
        Row reservedKb(long v) { this.reservedKb = v; return this; }
        Row usedKb(long v) { this.usedKb = v; return this; }
        Row dataKb(long v) { this.dataKb = v; return this; }
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
        // used 3800 = data 2400 + lob 300 + rowOverflow 100 + (derived index 1000)
        StorageInfo s = extract(new Row().table("INVOICE")
                .rowCount(12_500L).reservedKb(4_096L).usedKb(3_800L)
                .dataKb(2_400L).lobKb(300L).rowOverflowKb(100L)).get(0);

        assertThat(s.table()).isEqualTo("INVOICE");
        assertThat(s.schema()).isEqualTo("dbo");
        assertThat(s.rowCount()).isEqualTo(12_500L);
        assertThat(s.reservedKb()).isEqualTo(4_096L);
        assertThat(s.usedKb()).isEqualTo(3_800L);
        assertThat(s.dataKb()).isEqualTo(2_400L);
        assertThat(s.lobKb()).isEqualTo(300L);
        assertThat(s.rowOverflowKb()).isEqualTo(100L);
        // indexKb is the derived remainder: 3800 - 2400 - 300 - 100 = 1000
        assertThat(s.indexKb()).isEqualTo(1_000L);
    }

    @Test
    void usedDecomposition_dataIndexLobOverflowSumToUsed() throws SQLException {
        // For a consistent row the derived indexKb makes the decomposition
        // dataKb + indexKb + lobKb + rowOverflowKb == usedKb hold exactly.
        StorageInfo s = extract(new Row().usedKb(700L)
                .dataKb(400L).lobKb(80L).rowOverflowKb(20L)).get(0);

        assertThat(s.indexKb()).isEqualTo(200L);
        assertThat(s.dataKb() + s.indexKb() + s.lobKb() + s.rowOverflowKb())
                .isEqualTo(s.usedKb());
    }

    @Test
    void negativeRemainder_indexKbClampedToZero() throws SQLException {
        // Inconsistent source accounting (data + lob + rowOverflow > used):
        // the remainder is negative and indexKb must clamp to 0, never go below.
        StorageInfo s = extract(new Row()
                .usedKb(100L).dataKb(80L).lobKb(30L).rowOverflowKb(20L)).get(0);

        assertThat(s.indexKb()).isZero();
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

    // --- SQL shape (Codex 019e329a/019e34f9: mocked rows cannot prove the
    //     aggregation — assert the SQL string directly) ---

    @Test
    void extractStorage_sqlUsesCatalogViewsNotDmv() throws SQLException {
        extract(new Row());

        assertThat(capturedSql)
                .contains("sys.partitions")
                .contains("sys.allocation_units")
                .doesNotContain("sys.dm_db_partition_stats");
    }

    @Test
    void extractStorage_sqlSeparatesRowCountAndDecomposesByAllocationType()
            throws SQLException {
        extract(new Row());

        assertThat(capturedSql)
                // rowCount from a dedicated sys.partitions-only CTE — immune to
                // the allocation-unit fan-out.
                .contains("part_rows")
                // base heap / clustered index scoping
                .contains("index_id IN (0, 1)")
                // allocation-unit type decomposition: 1=in-row 2=lob 3=row-overflow
                .contains("au.type = 2")
                .contains("au.type = 3");
    }
}
