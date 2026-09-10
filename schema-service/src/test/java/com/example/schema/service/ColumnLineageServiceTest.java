package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import com.example.schema.service.ColumnLineageService.LineageGraph;
import com.example.schema.service.ColumnLineageService.LineageNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ColumnLineageServiceTest {

    private final ColumnLineageService service = new ColumnLineageService();

    private static SchemaSnapshot snapshot() {
        return SchemaSnapshot.builder()
            .tables(Map.of(
                "CHILD", new TableInfo("CHILD", "dbo", List.of(
                    new ColumnInfo("COMPANY", "varchar", 20, false, false, false, 1),
                    new ColumnInfo("EMP_NO", "varchar", 20, false, false, false, 2))),
                "COMPANY_PERSON", new TableInfo("COMPANY_PERSON", "dbo", List.of(
                    new ColumnInfo("COMPANY", "varchar", 20, false, false, true, 1),
                    new ColumnInfo("EMP_NO", "varchar", 20, false, false, true, 2)))))
            .relationships(List.of(
                new Relationship("CHILD", "EMP_NO", "COMPANY_PERSON", "EMP_NO", 1.0, "fk_constraint_composite", false,
                    List.of("COMPANY", "EMP_NO"), List.of("COMPANY", "EMP_NO"))))
            .build();
    }

    @Test
    @DisplayName("bileşik anahtarın ebeveyn kolonu da soyağacında görünür: CHILD.COMPANY ← COMPANY_PERSON.COMPANY")
    void everyPairOfACompositeKeyTracesToItsOwnColumn() {
        LineageGraph graph = service.traceColumn("CHILD", "COMPANY", snapshot(), Map.of());

        assertThat(graph.nodes()).contains(new LineageNode("COMPANY_PERSON", "COMPANY", "source"));
        assertThat(graph.nodes()).doesNotContain(new LineageNode("COMPANY_PERSON", "EMP_NO", "source"));
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().getFirst().transformation()).isEqualTo("FK reference");
    }

    @Test
    @DisplayName("hedef tarafta da çift bazında: COMPANY_PERSON.COMPANY tüketicisi CHILD.COMPANY'dir")
    void downstreamConsumersFollowThePairNotTheRepresentativeColumn() {
        LineageGraph graph = service.traceColumn("COMPANY_PERSON", "COMPANY", snapshot(), Map.of());

        assertThat(graph.nodes()).contains(new LineageNode("CHILD", "COMPANY", "consumer"));
        assertThat(graph.nodes()).doesNotContain(new LineageNode("CHILD", "EMP_NO", "consumer"));
    }
}
