package com.example.permission.dataaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Master data scope picker service — reports_db (workcube_mikrolink schema)
 * üzerinden direct SQL read.
 *
 * <p>Faz 21.3 follow-up (2026-04-29): Frontend ScopeAssignModal'da text input
 * yerine dropdown listesi için backend endpoint'leri. data_access.scope CHECK
 * constraint scope_kind ↔ scope_source_table mapping ile uyumlu:
 * <ul>
 *   <li>company  → workcube_mikrolink.our_company</li>
 *   <li>project  → workcube_mikrolink.pro_projects</li>
 *   <li>branch   → workcube_mikrolink.branch</li>
 *   <li>depot    → workcube_mikrolink.department</li>
 * </ul>
 *
 * <p>Tablolar boş olabilir (ETL henüz koşmamış); o zaman boş list döner.
 * UI bu durumu "veri yok" mesajı ile karşılar.
 *
 * <p>SQL guard: status filter (aktif kayıtlar). Pagination yok — master
 * data tipik ≤ 1000 row, frontend client-side filter.
 */
@Service
@ConditionalOnBean(name = "reportsDbJdbcTemplate")
public class MasterDataService {

    private static final Logger log = LoggerFactory.getLogger(MasterDataService.class);

    private final JdbcTemplate jdbc;

    public MasterDataService(@Qualifier("reportsDbJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Şirket listesi — workcube_mikrolink.our_company.
     * V25 anchor: tenant-scoped company tablosu.
     */
    public List<MasterDataItem> listCompanies() {
        return safeQuery(
                "SELECT comp_id, company_name, COALESCE(status, true) AS status "
                        + "FROM workcube_mikrolink.our_company "
                        + "ORDER BY company_name NULLS LAST, comp_id",
                "companies"
        );
    }

    /**
     * Proje listesi — workcube_mikrolink.pro_projects.
     */
    public List<MasterDataItem> listProjects() {
        return safeQuery(
                "SELECT project_id, project_name, COALESCE(project_status, true) AS status "
                        + "FROM workcube_mikrolink.pro_projects "
                        + "ORDER BY project_name NULLS LAST, project_id",
                "projects"
        );
    }

    /**
     * Şube listesi — workcube_mikrolink.branch.
     */
    public List<MasterDataItem> listBranches() {
        return safeQuery(
                "SELECT branch_id, branch_name, COALESCE(branch_status, true) AS status "
                        + "FROM workcube_mikrolink.branch "
                        + "ORDER BY branch_name NULLS LAST, branch_id",
                "branches"
        );
    }

    /**
     * Depo (depot) listesi — workcube_mikrolink.department.
     * Workcube'de DEPARTMENT multi-purpose (HR + fiziksel lokasyon); bu
     * endpoint scope picker için döndüğünden tüm department'ları listeler;
     * scope-kind filtering ileride opsiyonel filtre olabilir.
     */
    public List<MasterDataItem> listDepartments() {
        return safeQuery(
                "SELECT department_id, COALESCE(department_detail, department_head) AS name, "
                        + "COALESCE(department_status, true) AS status "
                        + "FROM workcube_mikrolink.department "
                        + "ORDER BY name NULLS LAST, department_id",
                "departments"
        );
    }

    private List<MasterDataItem> safeQuery(String sql, String label) {
        try {
            return jdbc.query(sql, (rs, rowNum) -> new MasterDataItem(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getBoolean(3)
            ));
        } catch (DataAccessException ex) {
            // Tablo yok / schema yok / connection problemi → boş list dönder.
            // Bu pattern reports_db ETL henüz koşmadığında veya schema drift'inde
            // UI'ın boş dropdown göstermesini sağlar (5xx yerine).
            log.warn("MasterData {} query failed (returning empty list); reason={}",
                    label, ex.getMostSpecificCause().getMessage());
            return Collections.emptyList();
        }
    }
}
