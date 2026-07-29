package com.example.ethics.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-403 — the catalog's two boundaries, locked before it grows (#885).
 *
 * <p>Both are properties the acceptance names, and both are the kind that hold today by
 * accident and stop holding the moment someone adds a reasonable-looking line.
 */
class CatalogBoundaryTest {

    private static final Path INTAKE =
            Path.of("src/main/java/com/example/ethics/api/PublicEthicsController.java");
    private static final Path SERVICE =
            Path.of("src/main/java/com/example/ethics/service/EthicsService.java");

    /**
     * <strong>Public intake must not consult the catalog.</strong>
     *
     * <p>A reporter has to be able to file even if the catalog, the subscription store or the
     * billing system is down. In an ordinary product an entitlement outage costs a feature;
     * here it would close the channel someone is using to report wrongdoing — the failure the
     * product exists to prevent. Measured today: {@code createReport} touches audit, cases,
     * grants, idempotency and notifications, and nothing else.
     *
     * <p>Asserted against the source text rather than at runtime because the danger is a
     * future edit, not current behaviour: a runtime test passes right up until someone adds
     * the check, and then fails somewhere far from the line they wrote.
     */
    @Test
    @DisplayName("public intake kataloğa hiç bakmaz")
    void thePublicIntakePathNeverConsultsTheCatalog() throws Exception {
        String controller = Files.readString(INTAKE);
        assertThat(controller)
                .as("public controller kataloğa bağlanmış — entitlement kesintisi ihbar "
                        + "kanalını kapatır")
                .doesNotContain("catalog")
                .doesNotContain("Catalog")
                .doesNotContain("EthicsCapability");

        // The service is shared between public and staff paths, so the file cannot simply be
        // clean. What must stay clean is the intake method itself.
        String service = Files.readString(SERVICE);
        int start = service.indexOf("public CreateReportResponse createReport");
        assertThat(start).as("createReport bulunamadı — test bayatlamış").isGreaterThan(0);
        int end = service.indexOf("\n    public ", start + 1);
        String createReport = service.substring(start, end > start ? end : service.length());

        assertThat(createReport)
                .as("createReport kataloğa bakıyor")
                .doesNotContain("catalog")
                .doesNotContain("Catalog")
                .doesNotContain("EthicsCapability");
    }

    /**
     * <strong>Entitlement is not authorization.</strong>
     *
     * <p>This class answers "did the organisation buy it"; whether a given person may act
     * belongs to the authorization model. Conflating them turns a lapsed subscription into
     * "you are not allowed", which is the wrong sentence to show a handler — and the wrong
     * record to leave in an audit trail.
     */
    @Test
    @DisplayName("katalog yetkilendirme kavramlarına hiç dokunmaz")
    void theCatalogHoldsNoAuthorizationConcepts() throws Exception {
        Path dir = Path.of("src/main/java/com/example/ethics/catalog");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                // Comments are stripped first. Naming the boundary in prose is the point —
                // these classes explain at length why entitlement is not authorization — and
                // a guard that forbade the word would push that explanation out of the file.
                // What must be absent is a dependency, not a mention.
                String source = stripComments(Files.readString(file));
                for (String forbidden : List.of(
                        "case_handler", "case_viewer", "case_triager",
                        "subject_reveal_approved", "evidence_reveal_approved",
                        "StaffContext", "authorization", "OpenFga")) {
                    assertThat(source)
                            .as("%s içinde yetkilendirme kavramı geçiyor: %s",
                                    file.getFileName(), forbidden)
                            .doesNotContain(forbidden);
                }
            }
        }
    }

    /** Removes block and line comments so the scan sees code, not prose. */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    /** An unresolvable product must contribute nothing, never everything. */
    @Test
    @DisplayName("bilinmeyen ürün yetenek vermez")
    void anUnknownProductGrantsNothing() {
        var catalog = new EthicsProductCatalog();
        assertThat(catalog.carries(Set.of("etik-speak-imaginary"), EthicsCapability.DATA_EXPORT))
                .isFalse();
        assertThat(catalog.carries(Set.of(), EthicsCapability.EVIDENCE_ATTACHMENTS)).isFalse();
        assertThat(catalog.carries(null, EthicsCapability.EVIDENCE_ATTACHMENTS)).isFalse();
    }

    /**
     * The capability whose misuse is least recoverable is sold on its own, so it cannot
     * arrive inside a bundle someone chose for other reasons.
     */
    @Test
    @DisplayName("konu-ifşası bir pakete gizlenmiş olarak gelmez")
    void subjectRevealIsNeverBundled() {
        var catalog = new EthicsProductCatalog();
        for (ProductDefinition product : catalog.all()) {
            if (!product.carries(EthicsCapability.SUBJECT_REVEAL)) continue;
            assertThat(product.capabilities())
                    .as("%s hem konu-ifşası hem başka yetenek taşıyor", product.id())
                    .containsExactly(EthicsCapability.SUBJECT_REVEAL);
        }
    }

    /** Every capability is sellable through some product; an orphan is a typo, not a plan. */
    @Test
    @DisplayName("her yetenek en az bir üründe satılabilir")
    void everyCapabilityIsReachableThroughSomeProduct() {
        var catalog = new EthicsProductCatalog();
        for (EthicsCapability capability : EthicsCapability.values()) {
            assertThat(catalog.all().stream().anyMatch(p -> p.carries(capability)))
                    .as("%s hiçbir üründe yok", capability)
                    .isTrue();
        }
    }
}
