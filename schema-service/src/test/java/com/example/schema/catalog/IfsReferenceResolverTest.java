package com.example.schema.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.schema.catalog.IfsReferenceResolver.ColumnMeta;
import com.example.schema.model.ForeignKeyInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * gitops#3631 slice 2 — IFS REF= becomes a foreign key, composite keys included, nothing guessed.
 * Every fixture shape below was measured on the live IFSAPP dictionary (2026-09-10, 28,440 references).
 */
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
            col("CONTRACT", 4, "FLAGS=A-IU-^PROMPT=Site^REF=Site/NOCHECK^"),
            col("NOTE", 5, "Free text written by hand"),
            col("LEGAL_BASE", 6, "FLAGS=A-IU-^PROMPT=Legal Base^REF=AbsLegalBase(company_id)^")));
        return views;
    }

    private static Map<String, ForeignKeyInfo> byName(IfsReferenceResolver.Result result) {
        Map<String, ForeignKeyInfo> byName = new LinkedHashMap<>();
        result.foreignKeys().forEach(fk -> byName.put(fk.name(), fk));
        return byName;
    }

    @Test
    @DisplayName("LU adı UPPER_SNAKE view adına çevrilir; rakamlar bitişik kalır; zaten büyük harfli ad olduğu gibi kalır")
    void luNamesBecomeViewNames() {
        assertThat(IfsReferenceResolver.luToViewName("Site")).isEqualTo("SITE");
        assertThat(IfsReferenceResolver.luToViewName("CompanyFinance")).isEqualTo("COMPANY_FINANCE");
        assertThat(IfsReferenceResolver.luToViewName("AbsLegalBase")).isEqualTo("ABS_LEGAL_BASE");
        assertThat(IfsReferenceResolver.luToViewName("Bill2Line")).isEqualTo("BILL2_LINE");
        // 346 live references write the view name itself (gitops#3643); the split made A_C_C_O_U_N_T_I_N_G_….
        assertThat(IfsReferenceResolver.luToViewName("ACCOUNTING_YEAR")).isEqualTo("ACCOUNTING_YEAR");
        assertThat(IfsReferenceResolver.luToViewName("ALL_LEDGER")).isEqualTo("ALL_LEDGER");
        assertThat(IfsReferenceResolver.luToViewName("Company_Finance")).isEqualTo("COMPANY_FINANCE");
    }

    @Test
    @DisplayName("ad kuralı view bulamazsa LU= indeksi: tek aday veya TABLE=X_TAB ile işaretli temel view; birden fazla temel-dışı aday belirsizdir")
    void luIndexResolvesWhatTheNameRuleMisses() {
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        views.put("DELIVERY_NOTE_JOIN", List.of(col("DELNOTE_NO", 1, "FLAGS=KMI-L^")));          // the LU's only visible view
        views.put("ENG_PART_MASTER_MAIN", List.of(col("PART_NO", 1, "FLAGS=KMI-L^")));            // base view among several
        views.put("ENG_PART_MASTER_ALT_LOV", List.of(col("PART_NO", 1, "FLAGS=KMI-L^")));
        views.put("RESOURCE_ACCESS_LOV", List.of(col("RESOURCE_ID", 1, "FLAGS=KMI-L^")));         // ambiguous: two non-base views
        views.put("MACHINE_SITE_CONN_UIV", List.of(col("RESOURCE_ID", 1, "FLAGS=KMI-L^")));
        views.put("COMPANY", List.of(col("COMPANY", 1, "FLAGS=KMI-L^")));
        views.put("USER_OF", List.of(
            col("DELNOTE_NO", 1, "FLAGS=A-IU-^REF=DeliveryNote^"),
            col("PART_NO", 2, "FLAGS=A-IU-^REF=EngPartMaster^"),
            col("RESOURCE_ID", 3, "FLAGS=A-IU-^REF=Resource^"),
            col("COMPANY", 4, "FLAGS=A-IU-^REF=Company^"),                  // name rule still wins when the view exists
            col("NOWHERE", 5, "FLAGS=A-IU-^REF=WageCode4^")));               // no view declares that LU
        Map<String, List<IfsReferenceResolver.LuView>> index = new LinkedHashMap<>();
        index.put("DeliveryNote", List.of(new IfsReferenceResolver.LuView("DELIVERY_NOTE_JOIN", false)));
        index.put("EngPartMaster", List.of(
            new IfsReferenceResolver.LuView("ENG_PART_MASTER_ALT_LOV", false),
            new IfsReferenceResolver.LuView("ENG_PART_MASTER_MAIN", true)));
        index.put("Resource", List.of(
            new IfsReferenceResolver.LuView("RESOURCE_ACCESS_LOV", false),
            new IfsReferenceResolver.LuView("MACHINE_SITE_CONN_UIV", false)));
        index.put("Company", List.of(new IfsReferenceResolver.LuView("COMPANY_FINANCE_LOV", false)));

        var result = IfsReferenceResolver.resolve("IFSAPP", views, index);

        var byName = byName(result);
        assertThat(byName.get("IFS_REF_USER_OF.DELNOTE_NO").toTable()).isEqualTo("DELIVERY_NOTE_JOIN");
        assertThat(byName.get("IFS_REF_USER_OF.PART_NO").toTable()).isEqualTo("ENG_PART_MASTER_MAIN");
        assertThat(byName.get("IFS_REF_USER_OF.COMPANY").toTable()).as("name rule first").isEqualTo("COMPANY");
        assertThat(byName).doesNotContainKeys("IFS_REF_USER_OF.RESOURCE_ID", "IFS_REF_USER_OF.NOWHERE");
        assertThat(result.resolvedViaLuIndex()).isEqualTo(2);
        assertThat(result.ambiguousTarget()).isEqualTo(1);
        assertThat(result.unresolvedTarget()).isEqualTo(1);
        assertThat(result.resolved()).isEqualTo(3);
        // Without an index the same input counts the three misses as unresolved targets, as before.
        assertThat(IfsReferenceResolver.resolve("IFSAPP", views).unresolvedTarget()).isEqualTo(4);
    }

    @Test
    @DisplayName("REF kuyruğu (/NOCHECK, /CASCADE, /CUSTOM=…) ad değildir; parantez kolonları büyük harfe çevrilir")
    void referenceTailIsNotPartOfTheName() {
        // 15,753 /NOCHECK + 1,505 /CASCADE + 297 /CUSTOM… of 28,440 live references carry a tail.
        var plain = IfsReferenceResolver.parseReference("Company");
        assertThat(plain.logicalUnit()).isEqualTo("Company");
        assertThat(plain.parentColumns()).isEmpty();
        assertThat(plain.tail()).isEmpty();

        var noCheck = IfsReferenceResolver.parseReference("AbsLegalBase(company_id)/NOCHECK");
        assertThat(noCheck.logicalUnit()).isEqualTo("AbsLegalBase");
        assertThat(noCheck.parentColumns()).containsExactly("COMPANY_ID");
        assertThat(noCheck.tail()).isEqualTo("NOCHECK");

        var custom = IfsReferenceResolver.parseReference("WorkSchedDayType(company_id,wage_class)/CUSTOM=(Check,Cascade)");
        assertThat(custom.parentColumns()).containsExactly("COMPANY_ID", "WAGE_CLASS");
        assertThat(custom.tail()).startsWith("CUSTOM=");

        assertThat(IfsReferenceResolver.parseReference("(broken")).isNull();
    }

    @Test
    @DisplayName("hedefin anahtarı: P ve K kolonları kolon sırasıyla — 164 view'da K, P'den önce gelir ve sıra korunur")
    void targetKeyIsEveryKeyColumnInColumnOrder() {
        assertThat(IfsReferenceResolver.keyNames(ifs().get("COMPANY_PERSON"))).containsExactly("COMPANY", "EMP_NO");
        assertThat(IfsReferenceResolver.keyNames(ifs().get("SITE"))).containsExactly("CONTRACT");
        // Given out of order and with the own key sitting before the parent key: column order wins.
        List<ColumnMeta> shuffled = List.of(
            col("NAME", 3, "FLAGS=AMIUL^"),
            col("COMPANY", 2, "FLAGS=PMI--^"),
            col("NODE_ID", 1, "FLAGS=KMI-L^"));
        assertThat(IfsReferenceResolver.keyNames(shuffled)).containsExactly("NODE_ID", "COMPANY");
        assertThat(IfsReferenceResolver.keyColumns(shuffled).getFirst().keyClass()).isEqualTo('K');
    }

    @Test
    @DisplayName("parantezsiz, yeniden adlandırılmış referans: K önce gelse bile kendi anahtar (K) kolonuna bağlanır; K yoksa/çoksa sayılır")
    void renamedReferenceTakesTheTargetsOwnKeyNotTheLastColumn() {
        // Codex 01a08afc iter-2 #2: PERSON's key is ID(K) then COMPANY(P); MANAGER_ID → Person must land on ID.
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        views.put("PERSON", List.of(
            col("ID", 1, "FLAGS=KMI-L^"),
            col("COMPANY", 2, "FLAGS=PMI--^"),
            col("MANAGER_ID", 3, "FLAGS=A-IU-^REF=Person^")));
        views.put("TWO_OWN", List.of(col("A", 1, "FLAGS=KMI-L^"), col("B", 2, "FLAGS=KMI-L^")));
        views.put("ALL_PARENT", List.of(col("COMPANY", 1, "FLAGS=PMI--^"), col("YEAR", 2, "FLAGS=PMI--^")));
        views.put("USER_OF", List.of(
            col("A", 1, "FLAGS=A-IU-^"), col("COMPANY", 2, "FLAGS=A-IU-^"),
            col("X", 3, "FLAGS=A-IU-^REF=TwoOwn^"),        // two K columns: which one is X? not guessed
            col("FISCAL", 4, "FLAGS=A-IU-^REF=AllParent^"))); // no K column at all: not guessed

        var result = IfsReferenceResolver.resolve("IFSAPP", views);

        var byName = byName(result);
        assertThat(byName).containsOnlyKeys("IFS_REF_PERSON.MANAGER_ID");
        assertThat(byName.get("IFS_REF_PERSON.MANAGER_ID").fromColumns()).containsExactly("MANAGER_ID", "COMPANY");
        assertThat(byName.get("IFS_REF_PERSON.MANAGER_ID").toColumns()).containsExactly("ID", "COMPANY");
        assertThat(result.unresolvedKeyShape()).isEqualTo(2);
        assertThat(result.unresolvedSourceColumn()).isZero();
    }

    @Test
    @DisplayName("tek kolonlu REF → tek kolonlu FK; parantezli REF → bileşik FK; hedefsiz REF sayılır, uydurulmaz")
    void referencesResolveToForeignKeysWithoutGuessing() {
        var result = IfsReferenceResolver.resolve("IFSAPP", ifs());

        // 5 REF= columns: COMPANY_PERSON.COMPANY, ABSENCE_REGISTRATION.{COMPANY, EMP_NO, CONTRACT, LEGAL_BASE}
        assertThat(result.references()).isEqualTo(5);
        assertThat(result.unresolvedTarget()).as("AbsLegalBase is not a view here").isEqualTo(1);
        assertThat(result.unresolvedKeyShape()).isZero();
        assertThat(result.unresolvedSourceColumn()).isZero();
        assertThat(result.resolved()).isEqualTo(4);

        var byName = byName(result);
        var site = byName.get("IFS_REF_ABSENCE_REGISTRATION.CONTRACT");
        assertThat(site.toTable()).isEqualTo("SITE");
        assertThat(site.fromColumns()).containsExactly("CONTRACT");
        assertThat(site.toColumns()).containsExactly("CONTRACT");
        assertThat(site.isComposite()).isFalse();
        assertThat(site.isNotTrusted()).as("IFS does not enforce it as a visible constraint").isTrue();

        var person = byName.get("IFS_REF_ABSENCE_REGISTRATION.EMP_NO");
        assertThat(person.toTable()).isEqualTo("COMPANY_PERSON");
        assertThat(person.fromColumns()).containsExactly("COMPANY", "EMP_NO");
        assertThat(person.toColumns()).containsExactly("COMPANY", "EMP_NO");
        assertThat(person.isComposite()).isTrue();

        assertThat(byName.get("IFS_REF_COMPANY_PERSON.COMPANY").toTable()).isEqualTo("COMPANY");
        assertThat(byName).doesNotContainKey("IFS_REF_ABSENCE_REGISTRATION.LEGAL_BASE");
        assertThat(byName).doesNotContainKey("IFS_REF_ABSENCE_REGISTRATION.NOTE");
    }

    @Test
    @DisplayName("çiftleme: aynı adlı kolonlar adla, kalanlar konumla eşlenir; çıktı hedef anahtar sırasındadır")
    void pairsByNameFirstThenByPosition() {
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        // Live: InventoryPartInStock's key is CONTRACT, PART_NO, …; CES_MOVE_PART lists REF=…(part_no,contract,…)
        views.put("INVENTORY_PART_IN_STOCK", List.of(
            col("CONTRACT", 1, "FLAGS=PMI--^"), col("PART_NO", 2, "FLAGS=PMI--^"), col("LOCATION_NO", 3, "FLAGS=KMI-L^")));
        views.put("CES_MOVE_PART", List.of(
            col("PART_NO", 1, "FLAGS=A-IU-^"), col("CONTRACT", 2, "FLAGS=A-IU-^"),
            col("KAYNAK_LOC", 3, "FLAGS=A-IU-^REF=InventoryPartInStock(part_no,contract)^")));
        // Live: AccountingCodePartA's key is COMPANY, CODE_PART, CODE_PART_VALUE; the parent list renames the middle one.
        views.put("ACCOUNTING_CODE_PART_A", List.of(
            col("COMPANY", 1, "FLAGS=PMI--^"), col("CODE_PART", 2, "FLAGS=PMI--^"), col("CODE_PART_VALUE", 3, "FLAGS=PMI--^")));
        views.put("ACCOUNT_CODE_A", List.of(
            col("COMPANY", 1, "FLAGS=PMI--^"), col("CONS_ACCNT", 2, "FLAGS=A-IU-^"),
            col("ACCOUNT", 3, "FLAGS=KMI-L^REF=AccountingCodePartA(COMPANY,CONS_ACCNT)/NOCHECK^")));

        var byName = byName(IfsReferenceResolver.resolve("IFSAPP", views));

        var stock = byName.get("IFS_REF_CES_MOVE_PART.KAYNAK_LOC");
        assertThat(stock.toColumns()).containsExactly("CONTRACT", "PART_NO", "LOCATION_NO");
        assertThat(stock.fromColumns()).as("names win over the listed order").containsExactly("CONTRACT", "PART_NO", "KAYNAK_LOC");

        var account = byName.get("IFS_REF_ACCOUNT_CODE_A.ACCOUNT");
        assertThat(account.toColumns()).containsExactly("COMPANY", "CODE_PART", "CODE_PART_VALUE");
        assertThat(account.fromColumns()).as("COMPANY by name, the other two by position").containsExactly("COMPANY", "CONS_ACCNT", "ACCOUNT");
        // A target key made only of parent columns (AccountingYear: COMPANY, ACCOUNTING_YEAR; K=0) is a key all the same.
        assertThat(IfsReferenceResolver.pair(List.of("COMPANY", "ACCOUNTING_YEAR"), List.of("COMPANY", "ACCOUNTING_YEAR")))
            .containsExactly("COMPANY", "ACCOUNTING_YEAR");
    }

    @Test
    @DisplayName("parantezsiz REF çok kolonlu bir anahtara giderse ebeveyn kolonları aynı adla kaynaktan alınır; yoksa sayılır")
    void implicitParentColumnsComeFromTheSourceByName() {
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        views.put("ACCOUNTING_ATTRIBUTE_VALUE", List.of(
            col("COMPANY", 1, "FLAGS=PMI--^"), col("ATTRIBUTE", 2, "FLAGS=PMI--^"), col("ATTRIBUTE_VALUE", 3, "FLAGS=KMI-L^")));
        // Live shape "paren=0 P=2 K=1" (644 references): the source carries COMPANY and ATTRIBUTE under the same names.
        views.put("ACCOUNTING_BALANCE_AUTH", List.of(
            col("COMPANY", 1, "FLAGS=PMI--^"), col("ATTRIBUTE", 2, "FLAGS=A-IU-^"),
            col("ATTRIBUTE_VALUE", 3, "FLAGS=A-IU-^REF=AccountingAttributeValue/NOCHECK^")));
        // Live shape "implicit parent missing" (149 references): no ATTRIBUTE column here.
        views.put("BALANCE_AUTH_SHORT", List.of(
            col("COMPANY", 1, "FLAGS=PMI--^"),
            col("ATTRIBUTE_VALUE", 2, "FLAGS=A-IU-^REF=AccountingAttributeValue^")));

        var result = IfsReferenceResolver.resolve("IFSAPP", views);

        assertThat(result.resolved()).isEqualTo(1);
        assertThat(result.unresolvedSourceColumn()).isEqualTo(1);
        var fk = result.foreignKeys().getFirst();
        assertThat(fk.fromTable()).isEqualTo("ACCOUNTING_BALANCE_AUTH");
        assertThat(fk.fromColumns()).containsExactly("COMPANY", "ATTRIBUTE", "ATTRIBUTE_VALUE");
        assertThat(fk.toColumns()).containsExactly("COMPANY", "ATTRIBUTE", "ATTRIBUTE_VALUE");
    }

    @Test
    @DisplayName("parantezdeki kolon kaynak view'da yoksa, tekrarlıysa veya referans kolonunun kendisiyse: kaynak-kolon hatası, FK yok")
    void parentColumnsMustExistInTheSourceView() {
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        views.put("COMPANY_PERSON", List.of(col("COMPANY", 1, "FLAGS=PMI--^"), col("EMP_NO", 2, "FLAGS=KMI-L^")));
        views.put("CHILD", List.of(
            col("EMP_NO", 1, "FLAGS=A-IU-^REF=CompanyPerson(no_such_column)^"),          // 339 live references name a missing column
            col("OTHER_EMP", 2, "FLAGS=A-IU-^REF=CompanyPerson(company,company)^"),
            col("SELF_EMP", 3, "FLAGS=A-IU-^REF=CompanyPerson(self_emp)^")));

        var result = IfsReferenceResolver.resolve("IFSAPP", views);

        assertThat(result.foreignKeys()).isEmpty();
        assertThat(result.unresolvedSourceColumn()).isEqualTo(3);
        assertThat(result.unresolvedKeyShape()).isZero();
    }

    @Test
    @DisplayName("kolon sayısı her iki yönde uyuşmazsa ve hedefin anahtarı yoksa: şekil hatası sayılır, uydurulmaz")
    void keyCountMismatchIsCountedNotGuessed() {
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        views.put("TWO_KEY", List.of(col("A", 1, "FLAGS=KMI-L^"), col("B", 2, "FLAGS=KMI-L^")));
        views.put("NO_KEY", List.of(col("X", 1, "FLAGS=AMIUL^")));
        views.put("USER_OF", List.of(
            col("P1", 1, "FLAGS=A-IU-^"), col("P2", 2, "FLAGS=A-IU-^"),
            col("TOO_MANY", 3, "FLAGS=A-IU-^REF=TwoKey(p1,p2)^"),     // 3 source columns for a 2-column key
            col("Z", 4, "FLAGS=A-IU-^REF=NoKey^")));                  // target without any key column
        views.put("USER_OF_TWO", List.of(
            col("A", 1, "FLAGS=A-IU-^"),
            col("Y", 2, "FLAGS=A-IU-^REF=TwoKey^")));                  // 1 source column, 2-column key with two K: which one is Y?

        var result = IfsReferenceResolver.resolve("IFSAPP", views);

        assertThat(result.foreignKeys()).isEmpty();
        assertThat(result.unresolvedKeyShape()).as("TOO_MANY, Z (keyless target), USER_OF_TWO.Y (two own keys)").isEqualTo(3);
        assertThat(result.unresolvedSourceColumn()).isZero();
    }

    @Test
    @DisplayName("kendine referans bir join'dir (MANAGER_ID → ID); her kolonu kendine eşleyen özdeşlik değildir")
    void selfReferencesJoinUnlessTheyAreTheIdentity() {
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        views.put("PERSON", List.of(
            col("ID", 1, "FLAGS=KMI-L^REF=Person^"),                      // identity: the key referencing itself (4 live)
            col("MANAGER_ID", 2, "FLAGS=A-IU-^REF=Person^")));            // hierarchy (126 live)
        views.put("ABSENCE_REGISTRATION", List.of(
            col("COMPANY", 1, "FLAGS=PMI--^"), col("EMP_NO", 2, "FLAGS=PMI--^"), col("ABSENCE_ID", 3, "FLAGS=KMI-L^"),
            col("ABSENCE_CONTINUATION_ID", 4, "FLAGS=A-IU-^REF=AbsenceRegistration(company,emp_no)^")));

        var result = IfsReferenceResolver.resolve("IFSAPP", views);

        var byName = byName(result);
        assertThat(byName).containsOnlyKeys("IFS_REF_PERSON.MANAGER_ID", "IFS_REF_ABSENCE_REGISTRATION.ABSENCE_CONTINUATION_ID");
        assertThat(byName.get("IFS_REF_PERSON.MANAGER_ID").fromColumns()).containsExactly("MANAGER_ID");
        assertThat(byName.get("IFS_REF_PERSON.MANAGER_ID").toColumns()).containsExactly("ID");
        assertThat(byName.get("IFS_REF_ABSENCE_REGISTRATION.ABSENCE_CONTINUATION_ID").fromColumns())
            .containsExactly("COMPANY", "EMP_NO", "ABSENCE_CONTINUATION_ID");
        assertThat(result.unresolvedKeyShape()).as("the identity is not a join").isEqualTo(1);
    }

    @Test
    @DisplayName("sentetik ad belirsiz değildir (A_B.C ≠ A.B_C — canlıda 2 çakışma); fazla parantez kolonu şekil hatasıdır")
    void namesAreUnambiguous() {
        Map<String, List<ColumnMeta>> views = new LinkedHashMap<>();
        views.put("SITE", List.of(col("CONTRACT", 1, "FLAGS=KMI-L^")));
        views.put("A_B", List.of(col("C", 1, "FLAGS=A-IU-^REF=Site^"), col("CONTRACT", 2, "FLAGS=AMIUL^")));
        views.put("A", List.of(col("B_C", 1, "FLAGS=A-IU-^REF=Site^")));
        views.put("TWICE", List.of(
            col("CONTRACT", 1, "FLAGS=PMI--^REF=Site^"),
            col("CONTRACT_AGAIN", 2, "FLAGS=A-IU-^REF=Site(contract)^")));  // resolves to the same pair set? no: 2 columns vs 1-key → shape

        var result = IfsReferenceResolver.resolve("IFSAPP", views);

        var names = result.foreignKeys().stream().map(ForeignKeyInfo::name).toList();
        assertThat(names).contains("IFS_REF_A_B.C", "IFS_REF_A.B_C", "IFS_REF_TWICE.CONTRACT");
        assertThat(names).doesNotHaveDuplicates();
        assertThat(result.duplicates()).isZero();
        assertThat(result.unresolvedKeyShape()).isEqualTo(1);
    }
}
