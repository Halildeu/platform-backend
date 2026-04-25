package com.example.report.workcube;

import java.util.Map;
import java.util.Set;

/**
 * Workcube MSSQL read-only allowlist (ADR-0005 Dual DataSource Reporting).
 *
 * <p>Sadece bu sınıfta listelenmiş tablo/view'ler MSSQL üzerinden sorgulanabilir.
 * Allowlist-only erişim → SQL injection ve veri sızıntısı riskini sınırlar.
 *
 * <p>Codex AGREE 019dc10b: "Allowlist + parametric queries only, no dynamic FROM."
 *
 * <p>Yeni view eklendiğinde:
 * <ol>
 *   <li>Workcube admin → DB user'a bu view için SELECT GRANT</li>
 *   <li>Bu sınıfta {@link #ALLOWED_VIEWS} map'ine eklenir (key: API ismi, value: gerçek view adı)</li>
 *   <li>İlgili kolon listesi {@link #SAFE_COLUMNS} map'ine eklenir (allowed projection)</li>
 *   <li>PR review + Codex retrospektif</li>
 * </ol>
 *
 * <p>İlk faz: küçük ve doğrulanabilir bir allowlist.
 * Faz 19.MSSQL.B (gelecek): Workcube admin tarafından tüm allowed view'ler enumerated edilince genişletilecek.
 */
public final class WorkcubeAllowlist {

    private WorkcubeAllowlist() {}

    /**
     * API key → MSSQL view name mapping (allowlist).
     *
     * <p>Key: REST endpoint path parametresi (örn. {@code /api/v1/workcube/{key}})
     * Value: MSSQL'deki gerçek schema.view (parametric değil — runtime'da güvenle string concat edilir)
     */
    public static final Map<String, String> ALLOWED_VIEWS = Map.of(
        // Faz 19.MSSQL.A — initial seed (Workcube admin doğrulayacak)
        "vw_company_summary", "dbo.vw_company_summary",
        "vw_user_directory",  "dbo.vw_user_directory",
        "vw_recent_orders",   "dbo.vw_recent_orders"
    );

    /**
     * View key → izinli kolon seti.
     *
     * <p>Boş set → tüm kolonlar (yine de allowlist içindeki view olduğu için risk yok).
     * Tanımlı set → SELECT projection bu set ile kısıtlanır (PII kolon hide vb.).
     */
    public static final Map<String, Set<String>> SAFE_COLUMNS = Map.of(
        "vw_company_summary", Set.of("company_id", "company_name", "active", "created_at"),
        "vw_user_directory",  Set.of("user_id", "display_name", "email", "department", "active"),
        "vw_recent_orders",   Set.of("order_id", "company_id", "order_date", "total_amount", "status")
    );

    /**
     * Allowlist'te mi?
     */
    public static boolean isAllowed(String key) {
        if (key == null) return false;
        return ALLOWED_VIEWS.containsKey(key);
    }

    /**
     * MSSQL view adı (allowlist'ten resolve).
     * @throws IllegalArgumentException allowlist'te değilse
     */
    public static String resolveView(String key) {
        String view = ALLOWED_VIEWS.get(key);
        if (view == null) {
            throw new IllegalArgumentException("Workcube view not in allowlist: " + key);
        }
        return view;
    }

    /**
     * İzinli kolonlar (boş ise tüm kolonlar).
     */
    public static Set<String> getAllowedColumns(String key) {
        return SAFE_COLUMNS.getOrDefault(key, Set.of());
    }
}
