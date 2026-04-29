package com.example.permission.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Codex 019dd818 iter-12 (Plan A+) regression coverage.
 *
 * <p>Locks the canonical legacy → catalog key mapping and the pass-through
 * behavior for unknown labels. Should this contract change (e.g. P2 catalog
 * expansion adds VARIANT, COMPANY), update both this test and
 * {@link ModuleNameCanonicalizer}.
 */
class ModuleNameCanonicalizerTest {

    @Test
    void mapsKullaniciYonetimiToUserManagement() {
        assertEquals("USER_MANAGEMENT", ModuleNameCanonicalizer.canonicalize("Kullanıcı Yönetimi"));
    }

    @Test
    void mapsAccessLabelToAccessKey() {
        assertEquals("ACCESS", ModuleNameCanonicalizer.canonicalize("Access"));
    }

    @Test
    void mapsSistemYonetimiToAccess() {
        // role/permission/scope management permissions are exposed as ACCESS
        // module in the role drawer. Whitelist for the 4 known codes is
        // enforced at the V14 migration level, not in the runtime canonicalizer.
        assertEquals("ACCESS", ModuleNameCanonicalizer.canonicalize("Sistem Yönetimi"));
    }

    @Test
    void mapsAuditLabelToAuditKey() {
        assertEquals("AUDIT", ModuleNameCanonicalizer.canonicalize("Audit"));
    }

    @Test
    void mapsReportingLowercaseToReport() {
        assertEquals("REPORT", ModuleNameCanonicalizer.canonicalize("reporting"));
    }

    @Test
    void mapsRaporlamaToReport() {
        assertEquals("REPORT", ModuleNameCanonicalizer.canonicalize("Raporlama"));
    }

    @Test
    void mapsDepoToWarehouse() {
        assertEquals("WAREHOUSE", ModuleNameCanonicalizer.canonicalize("Depo"));
    }

    @Test
    void mapsSatinAlmaToPurchase() {
        assertEquals("PURCHASE", ModuleNameCanonicalizer.canonicalize("Satın Alma"));
    }

    @Test
    void mapsTemaYonetimiToTheme() {
        assertEquals("THEME", ModuleNameCanonicalizer.canonicalize("Tema Yönetimi"));
    }

    @Test
    void preservesAlreadyCanonicalKeys() {
        // Idempotent: post-V14 migration values flow through unchanged.
        assertEquals("USER_MANAGEMENT", ModuleNameCanonicalizer.canonicalize("USER_MANAGEMENT"));
        assertEquals("ACCESS", ModuleNameCanonicalizer.canonicalize("ACCESS"));
        assertEquals("REPORT", ModuleNameCanonicalizer.canonicalize("REPORT"));
    }

    @Test
    void preservesUnmappedLabelsForP2Followup() {
        // Codex iter-12: VARIANT, COMPANY, scope.* are P2 catalog expansion
        // candidates — runtime should pass them through unchanged so the
        // existing WARN log surfaces the unknown key for ops awareness.
        assertEquals("Variant", ModuleNameCanonicalizer.canonicalize("Variant"));
        assertEquals("Company", ModuleNameCanonicalizer.canonicalize("Company"));
        assertEquals("scope", ModuleNameCanonicalizer.canonicalize("scope"));
    }

    @Test
    void replacesNullWithGenericKey() {
        assertEquals("GENERIC", ModuleNameCanonicalizer.canonicalize(null));
    }

    @Test
    void preservesArbitraryUnknownStrings() {
        // Defensive: strings not in the legacy or canonical sets pass through.
        assertEquals("CustomModule", ModuleNameCanonicalizer.canonicalize("CustomModule"));
        assertEquals("", ModuleNameCanonicalizer.canonicalize(""));
    }

    @Test
    void doesNotProduceMangledLabelDerivedKeys() {
        // Regression: Codex iter-11 explicit list of mangled keys that
        // deriveModuleIdentity must NOT emit anymore for known labels.
        // canonicalize() should map BEFORE deriveModuleIdentity sees the
        // value, so these mangled forms never reach the DTO contract.
        String[] inputs = {
                "Kullanıcı Yönetimi", "reporting", "Raporlama",
                "Sistem Yönetimi", "Depo", "Satın Alma", "Tema Yönetimi"
        };
        String[] mangledForbidden = {
                "KULLANICI_YONETIMI", "SISTEM_Y_NETIMI", "REPORTING",
                "RAPORLAMA", "SATIN_ALMA", "TEMA_YONETIMI", "DEPO"
        };
        for (String input : inputs) {
            String canonical = ModuleNameCanonicalizer.canonicalize(input);
            for (String forbidden : mangledForbidden) {
                if (canonical.equals(forbidden)) {
                    throw new AssertionError(
                            "canonicalize(\"" + input + "\") produced mangled key: " + forbidden
                    );
                }
            }
        }
    }
}
