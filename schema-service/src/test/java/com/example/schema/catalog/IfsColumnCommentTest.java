package com.example.schema.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** gitops#3631 — the IFS view-column comment is metadata, not prose. */
class IfsColumnCommentTest {

    @Test
    @DisplayName("FLAGS/DATATYPE/PROMPT/REF ayrıştırılır; P bayrağı anahtar kolon demektir")
    void structuredCommentIsParsed() {
        var c = IfsColumnComment.parse("FLAGS=PMI--^DATATYPE=STRING(20)/UPPERCASE^PROMPT=Company^REF=Company^");
        assertThat(c.structured()).isTrue();
        assertThat(c.label()).isEqualTo("Company");
        assertThat(c.keyColumn()).isTrue();
        assertThat(c.parentKeyColumn()).isFalse();
        assertThat(c.reference()).isEqualTo("Company");
        assertThat(c.entries().get(IfsColumnComment.DATATYPE)).isEqualTo("STRING(20)/UPPERCASE");
        assertThat(c.raw()).startsWith("FLAGS=PMI--");
    }

    @Test
    @DisplayName("A bayrağı özniteliktir: anahtar değil, etiket yine okunur")
    void attributeColumnIsNotAKey() {
        var c = IfsColumnComment.parse("FLAGS=A-IU-^DATATYPE=NUMBER^PROMPT=Voucher No^");
        assertThat(c.keyColumn()).isFalse();
        assertThat(c.parentKeyColumn()).isFalse();
        assertThat(c.label()).isEqualTo("Voucher No");
        assertThat(c.reference()).isNull();
    }

    @Test
    @DisplayName("K bayrağı ebeveyn anahtardır")
    void parentKeyFlag() {
        assertThat(IfsColumnComment.parse("FLAGS=KMI-L^PROMPT=Ledger ID^").parentKeyColumn()).isTrue();
        assertThat(IfsColumnComment.parse("FLAGS=KMI-L^PROMPT=Ledger ID^").keyColumn()).isFalse();
    }

    @Test
    @DisplayName("serbest metin yorum olduğu gibi kalır: etiket ve bayrak uydurulmaz")
    void freeTextIsKeptButNotInterpreted() {
        var c = IfsColumnComment.parse("  Customer facing note about this column  ");
        assertThat(c.structured()).isFalse();
        assertThat(c.raw()).isEqualTo("Customer facing note about this column");
        assertThat(c.label()).isNull();
        assertThat(c.keyColumn()).isFalse();
        assertThat(c.reference()).isNull();
    }

    @Test
    @DisplayName("boş/null yorum güvenli: raw null, boş girdi, hiçbir bayrak")
    void nullAndBlankAreSafe() {
        for (String s : new String[] {null, "", "   "}) {
            var c = IfsColumnComment.parse(s);
            assertThat(c.raw()).isNull();
            assertThat(c.entries()).isEmpty();
            assertThat(c.structured()).isFalse();
            assertThat(c.label()).isNull();
            assertThat(c.keyColumn()).isFalse();
        }
    }

    @Test
    @DisplayName("boş PROMPT= ve tekrarlanan anahtar: ilk değer kazanır, boş etiket null")
    void emptyPromptAndDuplicateKeys() {
        var c = IfsColumnComment.parse("PROMPT=^FLAGS=A----^FLAGS=PMI--^flags=ignored");
        assertThat(c.label()).isNull();
        assertThat(c.keyColumn()).isFalse();
        assertThat(c.entries().get("FLAGS")).isEqualTo("A----");
    }
}
