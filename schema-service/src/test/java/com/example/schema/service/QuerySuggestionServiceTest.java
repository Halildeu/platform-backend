package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import com.example.schema.service.QuerySuggestionService.QuerySuggestion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuerySuggestionServiceTest {

    private final QuerySuggestionService service = new QuerySuggestionService();

    private static SchemaSnapshot snapshot(List<Relationship> relationships) {
        return SchemaSnapshot.builder()
            .tables(Map.of(
                "CHILD", new TableInfo("CHILD", "dbo", List.of(
                    new ColumnInfo("COMPANY", "varchar", 20, false, false, false, 1),
                    new ColumnInfo("EMP_NO", "varchar", 20, false, false, false, 2))),
                "COMPANY_PERSON", new TableInfo("COMPANY_PERSON", "dbo", List.of(
                    new ColumnInfo("COMPANY", "varchar", 20, false, false, true, 1),
                    new ColumnInfo("EMP_NO", "varchar", 20, false, false, true, 2)))))
            .relationships(relationships)
            .build();
    }

    private static QuerySuggestion join(List<QuerySuggestion> suggestions) {
        return suggestions.stream().filter(s -> "join".equals(s.pattern())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("bileşik anahtar: JOIN her kolon çiftini AND ile bağlar (Codex 01a08afc P1)")
    void compositeJoinConditionCoversEveryPair() {
        var snapshot = snapshot(List.of(
            new Relationship("CHILD", "EMP_NO", "COMPANY_PERSON", "EMP_NO", 1.0, "fk_constraint_composite", false,
                List.of("COMPANY", "EMP_NO"), List.of("COMPANY", "EMP_NO"))));

        QuerySuggestion join = join(service.suggest("CHILD", snapshot));

        assertThat(join.sql()).contains("JOIN [dbo].[COMPANY_PERSON] r ON t.[COMPANY] = r.[COMPANY] AND t.[EMP_NO] = r.[EMP_NO]");
        assertThat(join.description()).isEqualTo("Join via CHILD.COMPANY, EMP_NO → COMPANY_PERSON");
    }

    @Test
    @DisplayName("tek kolonlu ilişki: JOIN koşulu tek çift, metin değişmedi")
    void singleColumnJoinIsUnchanged() {
        var snapshot = snapshot(List.of(
            new Relationship("CHILD", "EMP_NO", "COMPANY_PERSON", "EMP_NO", 0.9, "name_match_exact")));

        QuerySuggestion join = join(service.suggest("CHILD", snapshot));

        assertThat(join.sql()).contains("JOIN [dbo].[COMPANY_PERSON] r ON t.[EMP_NO] = r.[EMP_NO]\n");
        assertThat(join.description()).isEqualTo("Join via CHILD.EMP_NO → COMPANY_PERSON");
    }
}
