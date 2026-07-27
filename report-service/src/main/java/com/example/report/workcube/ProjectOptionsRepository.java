package com.example.report.workcube;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only Workcube project catalog scoped through the canonical
 * OUR_COMPANY <- COMPANY <- PRO_PROJECTS relationship.
 */
@Repository
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class ProjectOptionsRepository {

    private static final String SELECT_BY_OUR_COMPANY = """
            SELECT
                p.PROJECT_ID AS id,
                p.PROJECT_NUMBER AS code,
                p.PROJECT_HEAD AS name,
                oc.COMP_ID AS company_id,
                COALESCE(p.PROJECT_STATUS, 1) AS status
            FROM [workcube_mikrolink].[PRO_PROJECTS] p
            INNER JOIN [workcube_mikrolink].[COMPANY] cmp
                ON cmp.COMPANY_ID = p.COMPANY_ID
            INNER JOIN [workcube_mikrolink].[OUR_COMPANY] oc
                ON oc.COMP_ID = cmp.OUR_COMPANY_ID
            WHERE oc.COMP_ID = ?
              AND p.PROJECT_HEAD IS NOT NULL
              AND LEN(LTRIM(RTRIM(p.PROJECT_HEAD))) > 0
            ORDER BY p.PROJECT_HEAD, p.PROJECT_ID
            """;

    private final JdbcTemplate jdbc;

    public ProjectOptionsRepository(@Qualifier("workcubeMssqlPlainJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(cacheNames = "projectOptions", key = "#companyId", sync = true)
    public List<ProjectOption> findByCompanyId(long companyId) {
        return jdbc.query(
                SELECT_BY_OUR_COMPANY,
                (rs, rowNum) -> new ProjectOption(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getLong("company_id"),
                        rs.getInt("status") == 1),
                companyId);
    }

    public record ProjectOption(long id, String code, String name, long companyId, boolean active) {}
}
