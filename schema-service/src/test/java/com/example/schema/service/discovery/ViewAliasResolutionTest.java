package com.example.schema.service.discovery;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.TableInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * View-definition parsing after alias resolution became a single pass per view
 * (gitops#3594). The IFS ERP dictionary — 10,886 objects, 18MB of view SQL — is
 * where the previous per-match scan over every table name pinned the pod's CPU
 * limit until the liveness probe killed it.
 */
class ViewAliasResolutionTest {

    private static TableInfo table(String name, String... cols) {
        List<ColumnInfo> list = new ArrayList<>();
        for (int i = 0; i < cols.length; i++) {
            list.add(new ColumnInfo(cols[i], "VARCHAR2", 0, true, false, false, i + 1));
        }
        return new TableInfo(name, "IFSAPP", list);
    }

    private static RelationshipDiscoveryService service() {
        RelationshipDiscoveryService svc = new RelationshipDiscoveryService(Map.of(), Map.of());
        ReflectionTestUtils.setField(svc, "enableViewParsing", true);
        return svc;
    }

    private static List<Relationship> viewRels(List<Relationship> all) {
        return all.stream().filter(r -> r.source().startsWith("view_parse")).toList();
    }

    @Test
    void resolvesPlainAndAsAliasesAcrossConsecutiveJoins() {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        tables.put("CUSTOMER_ORDER", table("CUSTOMER_ORDER", "ORDER_NO", "CUSTOMER_ID"));
        tables.put("CUSTOMER_INFO", table("CUSTOMER_INFO", "CUSTOMER_ID"));
        tables.put("INVOICE", table("INVOICE", "ORDER_NO"));
        String view = "select co.order_no from customer_order co "
            + "join customer_info as ci on co.customer_id = ci.customer_id "
            + "join invoice inv on inv.order_no = co.order_no";

        List<Relationship> rels = viewRels(service().discoverAll(tables, Map.of("V", view), List.of()));

        assertThat(rels)
            .extracting(r -> r.fromTable() + "." + r.fromColumn() + "=" + r.toTable() + "." + r.toColumn())
            .containsExactlyInAnyOrder(
                "CUSTOMER_ORDER.CUSTOMER_ID=CUSTOMER_INFO.CUSTOMER_ID",
                "INVOICE.ORDER_NO=CUSTOMER_ORDER.ORDER_NO");
    }

    @Test
    void identifiersWithLatinIResolveUnderATurkishDefaultLocale() {
        // Default-locale toUpperCase() turns 'i' into U+0130 on tr_TR, which is
        // outside [A-Z] and \\w: CUSTOMER_INFO, INVOICE and alias ci all stop
        // matching and the parser silently returns nothing. The catalog is a
        // Turkish company's; the JVM locale must not decide what it parses.
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            resolvesPlainAndAsAliasesAcrossConsecutiveJoins();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void aliasDefinedOnALongerIdentifierDoesNotResolveToItsSuffix() {
        // The substring search resolved alias `co` to table X here, because
        // "X co" occurs inside "PREFIX_X co". Word boundaries close that.
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        tables.put("X", table("X", "ID"));
        tables.put("Y", table("Y", "ID"));
        String view = "select 1 from prefix_x co join y y on co.id = y.id";

        assertThat(viewRels(service().discoverAll(tables, Map.of("V", view), List.of()))).isEmpty();
    }

    @Test
    void manyTablesAndAliasHeavyViewsFinishInSeconds() {
        // 3,000 tables, 300 views, 30 aliased joins each. Under the per-match
        // scan this is on the order of 1e11 character comparisons; it must now
        // be linear in the text and sit comfortably inside a generous bound.
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        for (int i = 0; i < 3000; i++) {
            tables.put("T" + i, table("T" + i, "ID"));
        }
        Map<String, String> views = new LinkedHashMap<>();
        for (int v = 0; v < 300; v++) {
            StringBuilder sql = new StringBuilder("select * from T0 a0");
            for (int j = 1; j <= 30; j++) {
                sql.append(" join T").append((v * 31 + j) % 3000).append(" a").append(j)
                   .append(" on a").append(j - 1).append(".id = a").append(j).append(".id");
            }
            views.put("V" + v, sql.toString());
        }

        long start = System.nanoTime();
        List<Relationship> rels = viewRels(service().discoverAll(tables, views, List.of()));
        double seconds = (System.nanoTime() - start) / 1e9;

        assertThat(rels).isNotEmpty();
        assertThat(seconds).isLessThan(10.0);
    }
}
