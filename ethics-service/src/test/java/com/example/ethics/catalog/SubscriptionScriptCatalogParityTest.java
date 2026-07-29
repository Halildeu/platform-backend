package com.example.ethics.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-403 — the grant script and the catalog name the same products (#885).
 *
 * <p>The script validates the product id before writing, and its list is a second copy of the
 * catalog's. A value enumerated twice moves in one place and is lost in the other — that
 * happened this week with the notification event vocabulary (#1012), where the feature shipped
 * broken because a constant existed in Java and not in the database CHECK.
 *
 * <p>Here the failure would be quieter still. An unknown product id is accepted by the
 * database and carries no capability at all, because the catalog fails closed on ids it cannot
 * resolve. The customer would hold a subscription row and nothing else: a grant that looks
 * done and does nothing, discovered whenever someone tries to use the feature.
 */
class SubscriptionScriptCatalogParityTest {

    private static final Path SCRIPT = Path.of("../scripts/ops/etik-speak-subscription.sh");

    private static List<String> productsDeclaredInScript() throws Exception {
        String source = Files.readString(SCRIPT);
        int start = source.indexOf("PRODUCTS=(");
        assertThat(start).as("betikte PRODUCTS listesi bulunamadı — test bayatlamış").isGreaterThan(0);
        String block = source.substring(start, source.indexOf(')', start));

        List<String> declared = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(block);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        return declared;
    }

    @Test
    @DisplayName("betikteki ürün listesi katalogla birebir aynı")
    void theScriptAndTheCatalogAgreeOnEveryProduct() throws Exception {
        List<String> catalog = new EthicsProductCatalog().all().stream()
                .map(ProductDefinition::id)
                .sorted()
                .toList();
        List<String> script = productsDeclaredInScript().stream().sorted().toList();

        assertThat(script)
                .as("bağış betiği ile katalog ayrıştı: katalogda olmayan bir ürün yazılırsa "
                        + "abonelik satırı oluşur ama hiçbir yetenek taşımaz")
                .containsExactlyElementsOf(catalog);
    }

    /**
     * The approval reference lands in a row that can never be edited or deleted. Rejecting
     * spaces is what stops a sentence — and with it a person's name — from being pasted into
     * it; a contract or ticket reference is what belongs there.
     */
    @Test
    @DisplayName("onay referansı serbest metin kabul etmez")
    void theApprovalReferenceCannotBeFreeText() throws Exception {
        String source = Files.readString(SCRIPT);
        assertThat(source)
                .as("onay referansı biçim kontrolü kaldırılmış — deftere kişi adı yazılabilir")
                .contains("[A-Za-z0-9._/-]{3,64}");
    }

    /**
     * Every change must carry its justification into the ledger. A grant with no recorded
     * reason is one nobody can account for afterwards, which is the question an audit asks
     * first about the capability that reveals a reporter's subject.
     */
    @Test
    @DisplayName("onay referansı zorunlu ve deftere yazılıyor")
    void everyChangeCarriesItsJustificationIntoTheLedger() throws Exception {
        String source = Files.readString(SCRIPT);
        assertThat(source).contains("'approval_ref', '${APPROVAL_REF}'");
        assertThat(source)
                .as("onay referansı opsiyonel hale gelmiş")
                .contains("[[ -n \"$ORG\" && -n \"$PRODUCT\" && -n \"$APPROVAL_REF\" ]] || usage");
    }

    /**
     * The audit row's aggregate must be the subscription, not the organisation: the
     * classification derives ORG by looking the aggregate up in the subscription table, so an
     * org id here would be recorded as UNRESOLVED — the label meaning "the parent was already
     * gone" — and an erasure receipt would report a gap that never existed.
     */
    @Test
    @DisplayName("denetim olayının kümesi abonelik satırıdır, kurum değil")
    void theAuditAggregateIsTheSubscriptionRow() throws Exception {
        String source = Files.readString(SCRIPT);
        assertThat(source).contains("SELECT gen_random_uuid(), i.org_id, i.id, 'ethics.subscription.granted'");
        assertThat(source).contains("SELECT gen_random_uuid(), r.org_id, r.id, 'ethics.subscription.revoked'");
    }

    /** A revoked subscription keeps its row; the fact that it was once held is the record. */
    @Test
    @DisplayName("iptal satırı silmez")
    void revocationClosesTheWindowRatherThanRemovingTheRow() throws Exception {
        String source = Files.readString(SCRIPT);
        assertThat(source).contains("SET active = false, revoked_at = now()");
        assertThat(source)
                .as("betikte abonelik satırını silen bir ifade var")
                .doesNotContain("DELETE FROM ${SCHEMA}.ethics_org_subscription");
    }
}
