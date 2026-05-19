package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ReportDefinition;
import java.util.List;

/**
 * RC-001 — schemaMode=yearly requires non-empty yearColumn.
 *
 * <p>Yearly schema resolver tipik {@code workcube_mikrolink_{year}_{companyId}}
 * pattern'ini çözer; year resolution için {@code yearColumn} (örn. {@code RECORD_DATE},
 * {@code ACTION_DATE}) zorunlu — aksi halde AG-Grid tarih filtresinden yıl aralığı
 * türetilemez.
 *
 * <p><b>Dar carve-out (Codex thread 019e3f5c absorb)</b>: {@code COMPANY_REMAINDER}
 * bir bakiye anlık-görüntü tablosudur; yıl tamamen partition şema adında
 * ({@code workcube_mikrolink_{year}_{tenantId}}) kodludur ve raporun satır
 * düzeyinde tarih kolonu yoktur. {@code YearlySchemaResolver} boş bir
 * {@code yearColumn}'u zaten geçerli yıl partition'ına düşürür; bu raporlar için
 * {@code yearColumn} kavramsal olarak uygulanamaz. Carve-out yalnızca tablo
 * {@code source}'u {@code COMPANY_REMAINDER}, {@code sourceQuery} boş ve hiç
 * {@code date} tipli kolon yokken geçerlidir — {@code sourceQuery} raporları özel
 * SQL yüzeyi taşıdığından {@code yearColumn}'u açıkça bildirmek zorundadır.
 */
public final class RC001YearlyRequiresYearColumn implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-001";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        if (!"yearly".equalsIgnoreCase(def.schemaMode())) {
            return List.of();
        }
        if (hasText(def.yearColumn())) {
            return List.of();
        }
        if (isCompanyRemainderSchemaEncodedSnapshot(def)) {
            return List.of();
        }
        return List.of(ContractViolation.fail(
                ruleId(), def.key(), "yearColumn",
                "schemaMode=yearly requires non-empty yearColumn (year resolution source)"));
    }

    /**
     * Narrow RC-001 exemption (Codex 019e3f5c): a {@code COMPANY_REMAINDER}
     * balance snapshot whose year is encoded entirely in the partition schema
     * name and which exposes no {@code date}-typed column for an AG-Grid year
     * picker to filter on. {@code sourceQuery} reports are excluded — a custom
     * SQL surface must declare its {@code yearColumn} explicitly.
     */
    private static boolean isCompanyRemainderSchemaEncodedSnapshot(ReportDefinition def) {
        if (!"COMPANY_REMAINDER".equalsIgnoreCase(def.source())) {
            return false;
        }
        if (hasText(def.sourceQuery())) {
            return false;
        }
        if (def.columns() == null) {
            return false;
        }
        return def.columns().stream()
                .noneMatch(c -> "date".equalsIgnoreCase(c.type()));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
