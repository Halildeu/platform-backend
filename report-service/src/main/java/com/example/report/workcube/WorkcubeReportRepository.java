package com.example.report.workcube;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;

/**
 * Workcube MSSQL read-only repository (ADR-0005 Dual DataSource Reporting).
 *
 * <p>Sadece allowlist'te bulunan view'leri sorgular. Tüm sorgular:
 * <ul>
 *   <li>Parametric (named parameter) — SQL injection guard</li>
 *   <li>FROM klausu allowlist'ten resolve edilir (dynamic ama güvenli)</li>
 *   <li>SELECT projection allowed columns ile kısıtlanır</li>
 *   <li>30s query timeout + 10k row hard cap (config'de)</li>
 * </ul>
 *
 * <p>Activation: feature flag {@code report.mssql.enabled=true} ile birlikte
 * {@code workcubeMssqlJdbc} bean'i devreye girince bu repository de aktif olur.
 *
 * <p>Degraded mode: MSSQL unreachable → {@link DataAccessResourceFailureException}
 * controller layer'da 503 Service Unavailable'a çevrilir (PG endpoint'leri etkilenmez).
 */
@Repository
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class WorkcubeReportRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkcubeReportRepository.class);
    private static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 100;

    private final NamedParameterJdbcTemplate jdbc;

    public WorkcubeReportRepository(@Qualifier("workcubeMssqlJdbc") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Allowlist key ile view sorgu — TOP(N) ile limit + opsiyonel filtreler.
     *
     * @param key  Allowlist key (örn. "vw_company_summary")
     * @param filters Map of column → value (allowed columns ile kısıtlanır)
     * @param limit row limit (1..MAX_LIMIT)
     * @return rows
     */
    public List<Map<String, Object>> queryView(String key, Map<String, Object> filters, int limit) {
        if (!WorkcubeAllowlist.isAllowed(key)) {
            throw new IllegalArgumentException("Not in allowlist: " + key);
        }
        String viewName = WorkcubeAllowlist.resolveView(key);
        Set<String> allowedCols = WorkcubeAllowlist.getAllowedColumns(key);

        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        String projection = allowedCols.isEmpty() ? "*" : String.join(", ", allowedCols);

        StringBuilder sql = new StringBuilder("SELECT TOP(").append(safeLimit).append(") ");
        sql.append(projection).append(" FROM ").append(viewName).append(" WITH (NOLOCK)");

        MapSqlParameterSource params = new MapSqlParameterSource();
        if (filters != null && !filters.isEmpty()) {
            sql.append(" WHERE ");
            int i = 0;
            for (Map.Entry<String, Object> e : filters.entrySet()) {
                String col = e.getKey();
                // Filter column allowlist enforcement
                if (!allowedCols.isEmpty() && !allowedCols.contains(col)) {
                    throw new IllegalArgumentException("Filter column not allowed: " + col);
                }
                if (!col.matches("[a-zA-Z0-9_]+")) {
                    throw new IllegalArgumentException("Invalid column name: " + col);
                }
                if (i++ > 0) sql.append(" AND ");
                String pname = "p_" + col;
                sql.append(col).append(" = :").append(pname);
                params.addValue(pname, e.getValue());
            }
        }

        log.debug("Workcube query: {} (params: {})", sql, params.getValues());
        try {
            return jdbc.queryForList(sql.toString(), params);
        } catch (DataAccessResourceFailureException ex) {
            // Connectivity issues — bubble up so controller renders 503
            log.warn("Workcube MSSQL unavailable (degraded mode): {}", ex.getMessage());
            throw ex;
        }
    }

    /**
     * Allowlist key listesi (UI tooling için — endpoint discovery).
     */
    public List<Map<String, Object>> listAllowedViews() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : WorkcubeAllowlist.ALLOWED_VIEWS.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", e.getKey());
            row.put("view", e.getValue());
            row.put("columns", WorkcubeAllowlist.getAllowedColumns(e.getKey()));
            out.add(row);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Quick row count probe (sys.partitions — view yerine underlying table count).
     * Allowlist key bazlı, FROM dinamik olmadığı için güvenli.
     */
    public long countRows(String key) {
        if (!WorkcubeAllowlist.isAllowed(key)) {
            throw new IllegalArgumentException("Not in allowlist: " + key);
        }
        String view = WorkcubeAllowlist.resolveView(key);
        // SELECT COUNT(*) ile basit count; büyük view'lerde maliyetli olabilir,
        // gelecekte sys.partitions/sys.dm_db_partition_stats opt edilebilir
        String sql = "SELECT COUNT_BIG(*) AS c FROM " + view + " WITH (NOLOCK)";
        Long c = jdbc.getJdbcTemplate().queryForObject(sql, Long.class);
        return c != null ? c : 0L;
    }

    /** DataSource sentinel — health probe için boş ping. */
    public DataSource dataSource() {
        return jdbc.getJdbcTemplate().getDataSource();
    }
}
