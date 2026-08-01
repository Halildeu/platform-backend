package com.example.permission.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-0049 §3.4 — the sale boundary is machine-enforced, both directions (#885,
 * gitops#3178).
 *
 * <p>The failure this test exists for is not today's list being wrong; it is next quarter's
 * module being added to the catalog with nobody deciding whether it is core or sellable.
 * Without the decision the module would be unsellable-but-shipped-everywhere by accident —
 * the exact ambiguity the owner decision removed. So an unclassified key fails here, with a
 * message that says what decision is owed rather than which set literal to appease.
 */
class ModuleSaleClassificationTest {

    private final PermissionCatalogService catalog = new PermissionCatalogService();

    @Test
    @DisplayName("katalogdaki her modül sınıflandırılmış: CORE ya da SELLABLE(sku)")
    void everyCatalogModuleIsClassified() {
        List<String> unclassified = catalog.getModuleKeys().stream()
                .filter(key -> !ModuleSaleClassification.isCore(key)
                        && ModuleSaleClassification.sku(key).isEmpty())
                .toList();
        assertThat(unclassified)
                .as("Bu modüller için satış sınırı kararı verilmemiş. Yeni modül eklerken "
                        + "ADR-0049 §3 kararını aynı commit'te ver: ya CORE (her kurulumda, "
                        + "fiyatlanmaz) ya SELLABLE (hangi SKU satar?). Varsayılan yok.")
                .isEmpty();
    }

    @Test
    @DisplayName("sınıflandırma katalogda olmayan anahtar taşımaz (ölü kayıt yok)")
    void noClassificationForKeysOutsideTheCatalog() {
        Set<String> known = new HashSet<>(catalog.getModuleKeys());
        Set<String> orphans = new HashSet<>();
        ModuleSaleClassification.CORE.stream().filter(k -> !known.contains(k)).forEach(orphans::add);
        ModuleSaleClassification.SELLABLE.keySet().stream().filter(k -> !known.contains(k)).forEach(orphans::add);
        assertThat(orphans)
                .as("Katalogdan çıkmış anahtar sınıflandırmada kalmış — kaldır, yoksa liste "
                        + "gerçeği değil tarihi anlatır")
                .isEmpty();
    }

    @Test
    @DisplayName("hiçbir modül hem CORE hem SELLABLE değildir")
    void coreAndSellableAreDisjoint() {
        Set<String> both = new HashSet<>(ModuleSaleClassification.CORE);
        both.retainAll(ModuleSaleClassification.SELLABLE.keySet());
        assertThat(both).as("Bir modül iki tarafta birden olamaz").isEmpty();
    }

    /**
     * The core set is the owner decision verbatim. Not derived, not inferred — pinned. A PR
     * that moves AUDIT out of core must edit this test and say why in the diff.
     */
    @Test
    @DisplayName("çekirdek, owner kararının kendisidir — beşi de, fazlası değil")
    void theCoreIsExactlyTheOwnerDecision() {
        assertThat(ModuleSaleClassification.CORE).containsExactlyInAnyOrder(
                "USER_MANAGEMENT", "ACCESS", "AUDIT", "IMPERSONATION_AUDIT", "THEME");
    }

    /** ATS spans two modules on purpose; the SKU map must keep them under one purchase. */
    @Test
    @DisplayName("ATS SKU'su iki modülü birden taşır; Toplantı Zekâsı da")
    void multiModuleSkusStayWhole() {
        assertThat(ModuleSaleClassification.sku("ATS"))
                .isEqualTo(ModuleSaleClassification.sku("INTERVIEW_EVIDENCE"));
        assertThat(ModuleSaleClassification.sku("MEETING"))
                .isEqualTo(ModuleSaleClassification.sku("TRANSCRIPT"));
    }
}
