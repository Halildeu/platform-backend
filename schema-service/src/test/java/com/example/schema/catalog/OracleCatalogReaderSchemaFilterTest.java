package com.example.schema.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * gitops#3631 — the schema picker must not offer Oracle's own owners. Measured live: 12
 * owners returned, 11 of them Oracle-maintained (SYS, MDSYS, CTXSYS, ...), one business
 * schema (IFSAPP).
 */
class OracleCatalogReaderSchemaFilterTest {

    private static Map<String, Object> row(String name, int count) {
        return Map.of("name", name, "tableCount", count);
    }

    @Test
    @DisplayName("sözlük bayrağı varken de statik liste uygulanır: SYS/SYSTEM asla listelenmez")
    void oracleOwnedSchemasAreDroppedEvenWhenTheDictionaryReturnsThem() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(contains("ORACLE_MAINTAINED"), anyMap()))
            .thenReturn(List.of(row("IFSAPP", 10886), row("SYSTEM", 3), row("mdsys", 40), row("IFSINFO", 12)));

        var names = new OracleCatalogReader("ifs", jdbc, "IFSAPP").listSchemas().stream()
            .map(r -> r.get("name").toString()).toList();

        assertThat(names).containsExactly("IFSAPP", "IFSINFO");
    }

    @Test
    @DisplayName("12c öncesi (ORACLE_MAINTAINED yok): eski sorguya düşer, statik liste yine süzer")
    void fallsBackToTheStaticListBeforeTwelveC() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(contains("ORACLE_MAINTAINED"), anyMap()))
            .thenThrow(new BadSqlGrammarException("listSchemas", "select", new java.sql.SQLException("ORA-00904")));
        when(jdbc.queryForList(contains("GROUP BY OWNER"), anyMap()))
            .thenReturn(List.of(row("SYS", 2000), row("IFSAPP", 10886), row("XDB", 50), row("CTXSYS", 30)));

        var names = new OracleCatalogReader("ifs", jdbc, "IFSAPP").listSchemas().stream()
            .map(r -> r.get("name").toString()).toList();

        assertThat(names).containsExactly("IFSAPP");
    }

    @Test
    @DisplayName("statik listede tekrar yok ve ölçülen 11 sistem şemasının hepsi kapsanıyor")
    void staticListCoversTheMeasuredOwners() {
        for (String owner : List.of("SYS", "MDSYS", "CTXSYS", "WMSYS", "XDB", "OLAPSYS", "LBACSYS",
                "ORDSYS", "SYSTEM", "ORDDATA", "GSMADMIN_INTERNAL")) {
            assertThat(OracleCatalogReader.ORACLE_OWNED_SCHEMAS).contains(owner);
        }
        assertThat(OracleCatalogReader.ORACLE_OWNED_SCHEMAS).doesNotContain("IFSAPP");
    }
}
