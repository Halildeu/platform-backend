package com.example.schema.service;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gitops#3603 (Codex 01a0881d P2) — the local intent matcher mixes Turkish
 * natural language ("HANGİ TABLOLARDA") with SQL identifiers ("ORDER_ID"). The
 * two need different case rules: Turkish text lowered with ROOT keeps a
 * combining dot ("hangi̇") and never matches "hangi"; identifiers lowered with
 * Turkish rules turn "INVOICE" into "ınvoıce". Both intents must resolve under
 * a Turkish and under an English default locale — the answer must not depend
 * on the JVM's locale at all.
 */
class AiChatLocalAnswerLocaleTest {

    private Locale previous;
    private final AiChatService service = new AiChatService();
    private SchemaSnapshot snapshot;

    @BeforeEach
    void setUp() {
        previous = Locale.getDefault();
        TableInfo orders = new TableInfo("ORDERS", "dbo", List.of(
                new ColumnInfo("ORDER_ID", "int", 4, false, true, true, 1)));
        TableInfo invoice = new TableInfo("INVOICE", "dbo", List.of(
                new ColumnInfo("INVOICE_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("ORDER_ID", "int", 4, true, false, false, 2)));
        snapshot = SchemaSnapshot.builder()
                .version("v1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "h", "d", "s", Instant.now(), 2, 3, 0, 0))
                .tables(Map.of("ORDERS", orders, "INVOICE", invoice))
                .relationships(List.of())
                .domains(Map.of())
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();
    }

    @AfterEach
    void restore() {
        Locale.setDefault(previous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tr-TR", "en-US"})
    void upperCaseTurkishQuestionFindsTheColumn(String tag) {
        Locale.setDefault(Locale.forLanguageTag(tag));

        var r = service.chat("ORDER_ID HANGİ TABLOLARDA VAR?", snapshot);

        assertThat(r.aiGenerated()).isFalse();
        assertThat(r.referencedTables()).containsExactlyInAnyOrder("ORDERS", "INVOICE");
        assertThat(r.answer()).contains("ORDER_ID").contains("2 tabloda");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tr-TR", "en-US"})
    void upperCaseEnglishQuestionFindsTheColumn(String tag) {
        Locale.setDefault(Locale.forLanguageTag(tag));

        var r = service.chat("WHICH TABLE HAS INVOICE_ID?", snapshot);

        assertThat(r.referencedTables()).containsExactly("INVOICE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tr-TR", "en-US"})
    void tableNameWithLatinIIsRecognised(String tag) {
        Locale.setDefault(Locale.forLanguageTag(tag));

        var r = service.chat("INVOICE tablosu hakkında bilgi", snapshot);

        assertThat(r.aiGenerated()).isFalse();
        assertThat(r.answer()).startsWith("## INVOICE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tr-TR", "en-US"})
    void tableCountQuestionInUpperCaseTurkish(String tag) {
        Locale.setDefault(Locale.forLanguageTag(tag));

        var r = service.chat("KAÇ TABLO VAR?", snapshot);

        assertThat(r.answer()).contains("2 tablo");
    }
}
