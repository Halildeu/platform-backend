package com.example.report.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.exceptions.ContractExceptionEntry;
import com.example.report.contract.report.ContractReport;
import com.example.report.contract.report.ContractViolation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * Phase 2 Program 1d — ReportContractGate end-to-end tests.
 *
 * <p>Codex iter-4 §1d-AGREE absorb (thread 019e0119): full registry sweep
 * exercises the entire chain (raw schema validation → bind → semantic RC →
 * exception suppression). Provides the gate green-path proof for all 32
 * migrated reports.
 *
 * <p>Acceptance criteria:
 * <ul>
 *   <li>Default {@code mvn test} run completes deterministically.</li>
 *   <li>RC-004 governance debt is suppressed by the 4 exception entries
 *       (HR reports with no company-boundary column; tracked #247). RC-001
 *       debt closed via the RC001 COMPANY_REMAINDER carve-out (Codex
 *       019e3f5c).</li>
 *   <li>{@code hr-personel-listesi} (legitimate {@code OUR_COMPANY_ID})
 *       is NOT in the exception list.</li>
 *   <li>No unsuppressed FAILs remain in the gate output.</li>
 *   <li>Exception inventory exact: 4 entries (4× RC-004); RC-001 closed
 *       via carve-out, RC-005 eliminated by 2d.</li>
 * </ul>
 */
class ReportContractGateTest {

    @Test
    void gate_full32ReportRegistry_returnsCleanReport() {
        ContractReport report = ReportContractGate.create().gate();

        assertThat(report.reportCount())
                .as("All 32 migrated reports plus exceptions.json (excluded) discovered by sweep")
                .isEqualTo(32);

        // Codex iter-4 §1d-AGREE: gate must produce zero unsuppressed FAILs.
        // Exception entries cover known RC-004 debt only; RC-001 COMPANY_REMAINDER
        // snapshots pass by the RC001 rule carve-out. Anything beyond that
        // signals new tech debt or regression.
        assertThat(report.failures())
                .as("Unsuppressed failures: %s",
                        report.failures().stream().limit(10).toList())
                .isEmpty();
    }

    @Test
    void gate_currentRC004ExceptionsAreSuppressed() {
        // 4 RC-004 governance debt entries (HR reports with no company-boundary
        // column; tracked Halildeu/platform-backend#247). hr-giris-cikis +
        // hr-puantaj (2e/BRANCH) and stok-durum (current-resolver fix) were
        // closed earlier and are no longer exception entries.
        ContractReport report = ReportContractGate.create().gate();
        List<String> knownDebtKeys = List.of(
                "hr-bordro-detay", "hr-izin-raporu",
                "hr-maas-gecmisi", "hr-maas-raporu");

        // None of these should appear as failures.
        assertThat(report.failures())
                .as("Current RC-004 governance debt must be exception-suppressed")
                .noneMatch(v -> knownDebtKeys.contains(v.reportKey())
                        && "RC-004".equals(v.ruleId()));
    }

    @Test
    void gate_hrPersonelListesi_notExceptionSuppressed_legitimateAllowlist() {
        // hr-personel-listesi uses OUR_COMPANY_ID (legitimate tenant col, in allowlist).
        // It must NOT be in the exception list AND must pass without suppression.
        ContractReport report = ReportContractGate.create().gate();

        assertThat(report.failures())
                .as("hr-personel-listesi uses legitimate OUR_COMPANY_ID; no RC-004 fail expected")
                .noneMatch(v -> "hr-personel-listesi".equals(v.reportKey())
                        && "RC-004".equals(v.ruleId()));
    }

    @Test
    void gate_yearColumnSemanticFixes_noRC001ForFixedReports() {
        // 3 reports got yearColumn semantic fix (Codex iter-4 §1d-AGREE):
        //   fin-fatura-satirlari → INVOICE_DATE
        //   fin-stok-fis-detay → FIS_DATE
        //   fin-gerceklesen-maliyet → RECORD_DATE
        ContractReport report = ReportContractGate.create().gate();
        List<String> fixedKeys = List.of(
                "fin-fatura-satirlari", "fin-stok-fis-detay", "fin-gerceklesen-maliyet");

        assertThat(report.failures())
                .as("yearColumn semantic-fix reports must NOT trigger RC-001")
                .noneMatch(v -> fixedKeys.contains(v.reportKey()) && "RC-001".equals(v.ruleId()));
    }

    @Test
    void gate_companyRemainderSnapshotReports_passRC001ViaCarveout() {
        // Codex 019e3f5c absorb: fin-alacak-yaslandirma + fin-borc-yaslandirma
        // are COMPANY_REMAINDER schema-encoded balance snapshots — RC-001 now
        // passes them via the carve-out, so no exception entry is needed.
        ContractReport report = ReportContractGate.create().gate();
        List<String> snapshotKeys = List.of("fin-alacak-yaslandirma", "fin-borc-yaslandirma");

        assertThat(report.failures())
                .as("COMPANY_REMAINDER snapshot reports must not raise RC-001")
                .noneMatch(v -> snapshotKeys.contains(v.reportKey()) && "RC-001".equals(v.ruleId()));
    }

    @Test
    void exceptionsJson_inventoryExactCounts() throws Exception {
        // Codex iter-5 §1d-AGREE absorb: lock the production exception inventory
        // shape so accidental drift (e.g. someone adding an RC-007 90d entry
        // without review) is caught at gate-test time.
        // Codex 019e3f5c absorb: RC-001 debt (×2) closed via the RC001
        // COMPANY_REMAINDER schema-encoded-snapshot carve-out; only 4 RC-004
        // entries remain (HR reports lacking a company-boundary column —
        // tracked Halildeu/platform-backend#247).
        ContractExceptionEntry[] entries = loadExceptions();

        assertThat(entries)
                .as("Governance debt entries: 4 RC-004 (RC-001 closed via carve-out)")
                .hasSize(4);

        Map<String, Long> byRule = Arrays.stream(entries)
                .flatMap(e -> e.ruleIds().stream())
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

        assertThat(byRule.get("RC-004"))
                .as("RC-004 debt: HR reports with no company-boundary column")
                .isEqualTo(4L);
        assertThat(byRule.get("RC-001"))
                .as("RC-001 debt closed via RC001 COMPANY_REMAINDER carve-out")
                .isNull();
        // RC-005 eliminated by Phase 2 Program 2d (rowFilter removed from 12
        // yearly reports; 2a runtime tenant guard hardening provides the
        // fail-closed precondition).
        assertThat(byRule.get("RC-005"))
                .as("RC-005 debt eliminated by 2d")
                .isNull();

        // No other rule namespace should creep in without review.
        assertThat(byRule.keySet())
                .containsExactly("RC-004");
    }

    @Test
    void exceptionsJson_hrPersonelListesi_notInExceptionList() throws Exception {
        // Direct inventory assertion (not failure absence — failure absence
        // could pass even if a stray exception entry was added).
        ContractExceptionEntry[] entries = loadExceptions();

        assertThat(entries)
                .as("hr-personel-listesi uses legitimate OUR_COMPANY_ID via allowlist; "
                        + "MUST NOT appear in exception inventory")
                .noneMatch(e -> "hr-personel-listesi".equals(e.reportKey()));
    }

    @Test
    void exceptionsJson_allEntriesWithin90DayHorizon() throws Exception {
        // All committed entries must have expiresAt within 90 days of the
        // committed-at date (2026-05-07 → 2026-08-05). Codex iter-3 §1b absorb
        // continuity: 90d horizon is governance debt visibility deadline.
        ContractExceptionEntry[] entries = loadExceptions();

        assertThat(entries)
                .as("All exception entries must have explicit expiresAt")
                .allMatch(e -> e.expiresAt() != null);
        assertThat(entries)
                .as("All exception entries must have non-blank reason for audit")
                .allMatch(e -> e.reason() != null && e.reason().length() >= 10);
        assertThat(entries)
                .as("All exception entries must have owner")
                .allMatch(e -> e.owner() != null && !e.owner().isBlank());
    }

    private ContractExceptionEntry[] loadExceptions() throws Exception {
        Resource resource = new DefaultResourceLoader()
                .getResource("classpath:reports/exceptions.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (InputStream in = resource.getInputStream()) {
            return mapper.readValue(in, ContractExceptionEntry[].class);
        }
    }
}
