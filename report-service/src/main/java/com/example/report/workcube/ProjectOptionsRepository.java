package com.example.report.workcube;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only Workcube project catalog.
 *
 * <p>{@code PRO_PROJECTS.COMPANY_ID} is the project's customer/current-account
 * company in a material part of the live Workcube catalog; it is therefore not
 * a reliable owner-tenant boundary by itself. The picker combines:
 *
 * <ul>
 *   <li>the legacy canonical {@code OUR_COMPANY <- COMPANY <- PRO_PROJECTS}
 *       relationship, and</li>
 *   <li>project IDs actually referenced by the selected tenant's yearly
 *       {@code ACCOUNT_CARD_ROWS} schemas.</li>
 * </ul>
 *
 * <p>The second branch is still fail-closed on the selected company because
 * the company ID is encoded in the validated yearly schema name. All statements
 * are read-only.
 */
@Repository
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class ProjectOptionsRepository {

    private static final Pattern YEARLY_ACCOUNTING_SCHEMA =
            Pattern.compile("workcube_mikrolink_\\d{4}_\\d+");

    private static final String DISCOVER_ACCOUNTING_SCHEMAS = """
            SELECT TABLE_SCHEMA
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_NAME = N'ACCOUNT_CARD_ROWS'
              AND TABLE_TYPE = N'BASE TABLE'
              AND TABLE_SCHEMA LIKE ?
            ORDER BY TABLE_SCHEMA
            """;

    private final JdbcTemplate jdbc;

    public ProjectOptionsRepository(@Qualifier("workcubeMssqlPlainJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Cacheable(cacheNames = "projectOptions", key = "#companyId", sync = true)
    public List<ProjectOption> findByCompanyId(long companyId) {
        List<String> accountingSchemas = findAccountingSchemas(companyId);
        String sql = buildProjectCatalogSql(accountingSchemas);
        return jdbc.query(
                sql,
                (rs, rowNum) -> new ProjectOption(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getLong("company_id"),
                        rs.getInt("status") == 1),
                companyId,
                companyId);
    }

    private List<String> findAccountingSchemas(long companyId) {
        Pattern exactSchema =
                Pattern.compile("workcube_mikrolink_\\d{4}_" + companyId);
        return jdbc.queryForList(
                        DISCOVER_ACCOUNTING_SCHEMAS,
                        String.class,
                        "workcube_mikrolink_%_" + companyId)
                .stream()
                .filter(schema -> exactSchema.matcher(schema).matches())
                .distinct()
                .sorted()
                .toList();
    }

    static String buildProjectCatalogSql(List<String> accountingSchemas) {
        StringBuilder projectIds = new StringBuilder("""
                SELECT p.PROJECT_ID
                FROM [workcube_mikrolink].[PRO_PROJECTS] p
                INNER JOIN [workcube_mikrolink].[COMPANY] cmp
                    ON cmp.COMPANY_ID = p.COMPANY_ID
                INNER JOIN [workcube_mikrolink].[OUR_COMPANY] oc
                    ON oc.COMP_ID = cmp.OUR_COMPANY_ID
                WHERE oc.COMP_ID = ?
                  AND p.PROJECT_HEAD IS NOT NULL
                  AND LEN(LTRIM(RTRIM(p.PROJECT_HEAD))) > 0
                """);

        for (String schema : accountingSchemas) {
            // Schema names come only from INFORMATION_SCHEMA and must match
            // workcube_mikrolink_<four-digit-year>_<numeric-company-id>.
            if (!YEARLY_ACCOUNTING_SCHEMA.matcher(schema).matches()) {
                throw new IllegalArgumentException(
                        "Unexpected yearly accounting schema name");
            }
            projectIds.append("\nUNION\n")
                    .append("SELECT acr.ACC_PROJECT_ID\n")
                    .append("FROM [")
                    .append(schema)
                    .append("].[ACCOUNT_CARD_ROWS] acr\n")
                    .append("WHERE acr.ACC_PROJECT_ID IS NOT NULL\n")
                    .append("  AND acr.ACC_PROJECT_ID > 0\n");
        }

        return """
                WITH company_project_ids AS (
                %s
                )
                SELECT
                    ids.PROJECT_ID AS id,
                    COALESCE(
                        NULLIF(LTRIM(RTRIM(p.PROJECT_NUMBER)), N''),
                        CONVERT(NVARCHAR(30), ids.PROJECT_ID)
                    ) AS code,
                    COALESCE(
                        NULLIF(LTRIM(RTRIM(p.PROJECT_HEAD)), N''),
                        N'Katalog kaydı eksik — Proje '
                            + CONVERT(NVARCHAR(30), ids.PROJECT_ID)
                    ) AS name,
                    ? AS company_id,
                    COALESCE(p.PROJECT_STATUS, 1) AS status
                FROM company_project_ids ids
                LEFT JOIN [workcube_mikrolink].[PRO_PROJECTS] p
                    ON p.PROJECT_ID = ids.PROJECT_ID
                ORDER BY
                    CASE
                        WHEN NULLIF(LTRIM(RTRIM(p.PROJECT_HEAD)), N'') IS NULL
                            THEN 1
                        ELSE 0
                    END,
                    p.PROJECT_HEAD,
                    ids.PROJECT_ID
                """.formatted(projectIds);
    }

    public record ProjectOption(long id, String code, String name, long companyId, boolean active) {}
}
