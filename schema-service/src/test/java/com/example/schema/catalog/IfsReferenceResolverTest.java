package com.example.schema.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.schema.catalog.IfsReferenceResolver.ColumnMeta;
import com.example.schema.model.ForeignKeyInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** gitops#3631 slice 2 — IFS REF= becomes a foreign key, composite keys included, nothing guessed. */
class IfsReferenceResolverTest {

    private static ColumnMeta col(String name, int ordinal, String comment) {
        return new ColumnMeta(name, ordinal, IfsColumnComment.parse(comment));
    }

    private static Map<String, List<ColumnMeta>> ifs() {
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        views.put("SITE", List.of(
            col("CONTRACT", 1, "FLAGS=KMI-L^DATATYPE=STRING(5)/UPPERCASE^PROMPT=Site^"),
            col("DESCRIPTION", 2, "FLAGS=AMIUL^PROMPT=Description^")));
        views.put("COMPANY", List.of(
            col("COMPANY", 1, "FLAGS=KMI-L^PROMPT=Company^")));
        // A child LU: parent key COMPANY (P) + own key EMP_NO (K)
        views.put("COMPANY_PERSON", List.of(
            col("COMPANY", 1, "FLAGS=PMI--^PROMPT=Company^REF=Company^"),
            col("EMP_NO", 2, "FLAGS=KMI-L^PROMPT=Employee No^"),
            col("NAME", 3, "FLAGS=AMIUL^PROMPT=Name^")));
        views.put("ABSENCE_REGISTRATION", List.of(
            col("COMPANY", 1, "FLAGS=PMI--^PROMPT=Company^REF=Company^"),
            col("EMP_NO", 2, "FLAGS=PMI--^PROMPT=Employee No^REF=CompanyPerson(company)^"),
            col("ABSENCE_ID", 3, "FLAGS=KMI-L^PROMPT=Absence Id^"),
            col("CONTRACT", 4, "FLAGS=A-IU-^PROMPT=Site^REF=Site^"),
            col("NOTE", 5, "Free text written by hand"),
            col("LEGAL_BASE", 6, "FLAGS=A-IU-^PROMPT=Legal Base^REF=AbsLegalBase(company_id)^")));
        return views;
    }

    @Test
    @DisplayName("LU adı UPPER_SNAKE view adına çevrilir; rakamlar bitişik kalır")
    void luNamesBecomeViewNames() {
        assertThat(IfsReferenceResolver.luToViewName("Site")).isEqualTo("SITE");
        assertThat(IfsReferenceResolver.luToViewName("CompanyFinance")).isEqualTo("COMPANY_FINANCE");
        assertThat(IfsReferenceResolver.luToViewName("AbsLegalBase")).isEqualTo("ABS_LEGAL_BASE");
        assertThat(IfsReferenceResolver.luToViewName("Bill2Line")).isEqualTo("BILL2_LINE");
    }

    @Test
    @DisplayName("hedefin anahtarı: ebeveyn (P) kolonları önce, kendi (K) kolonları sonra, sütun sırasıyla")
    void targetKeyIsParentThenOwn() {
        assertThat(IfsReferenceResolver.keyColumns(ifs().get("COMPANY_PERSON"))).containsExactly("COMPANY", "EMP_NO");
        assertThat(IfsReferenceResolver.keyColumns(ifs().get("SITE"))).containsExactly("CONTRACT");
    }

    @Test
    @DisplayName("tek kolonlu REF → tek kolonlu FK; parantezli REF → bileşik FK; hedefsiz/şekilsiz REF sayılır, uydurulmaz")
    void referencesResolveToForeignKeysWithoutGuessing() {
        var result = IfsReferenceResolver.resolve("IFSAPP", ifs());

        // 5 REF= columns: COMPANY_PERSON.COMPANY, ABSENCE_REGISTRATION.{COMPANY, EMP_NO, CONTRACT, LEGAL_BASE}
        assertThat(result.references()).isEqualTo(5);
        assertThat(result.unresolvedTarget()).as("AbsLegalBase is not a view here").isEqualTo(1);
        assertThat(result.unresolvedKeyShape()).isZero();
        assertThat(result.resolved()).isEqualTo(4);

        Map<String, ForeignKeyInfo> byName = new LinkedHashMap<>();
        result.foreignKeys().forEach(fk -> byName.put(fk.name(), fk));

        var site = byName.get("IFS_REF_ABSENCE_REGISTRATION_CONTRACT");
        assertThat(site.toTable()).isEqualTo("SITE");
        assertThat(site.fromColumns()).containsExactly("CONTRACT");
        assertThat(site.toColumns()).containsExactly("CONTRACT");
        assertThat(site.isComposite()).isFalse();
        assertThat(site.isNotTrusted()).as("IFS does not enforce it as a visible constraint").isTrue();

        var person = byName.get("IFS_REF_ABSENCE_REGISTRATION_EMP_NO");
        assertThat(person.toTable()).isEqualTo("COMPANY_PERSON");
        assertThat(person.fromColumns()).containsExactly("COMPANY", "EMP_NO");
        assertThat(person.toColumns()).containsExactly("COMPANY", "EMP_NO");
        assertThat(person.isComposite()).isTrue();

        assertThat(byName.get("IFS_REF_COMPANY_PERSON_COMPANY").toTable()).isEqualTo("COMPANY");
        assertThat(byName).doesNotContainKey("IFS_REF_ABSENCE_REGISTRATION_LEGAL_BASE");
        assertThat(byName).doesNotContainKey("IFS_REF_ABSENCE_REGISTRATION_NOTE");
    }

    @Test
    @DisplayName("anahtar sayısı uyuşmayan referans uydurulmaz, şekil hatası sayılır")
    void keyCountMismatchIsCountedNotGuessed() {
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        views.put("TWO_KEY", List.of(col("A", 1, "FLAGS=KMI-L^"), col("B", 2, "FLAGS=KMI-L^")));
        views.put("USER_OF", List.of(col("X", 1, "FLAGS=A-IU-^REF=TwoKey^")));

        var result = IfsReferenceResolver.resolve("IFSAPP", views);

        assertThat(result.foreignKeys()).isEmpty();
        assertThat(result.unresolvedKeyShape()).isEqualTo(1);
    }
}
