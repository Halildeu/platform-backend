package com.example.report.workcube;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;

/**
 * Read-only catalog of Workcube companies for the muavin / dynamic-report
 * CompanyPicker dropdown.
 *
 * <p>Workcube is per-company multi-tenant: every {@code workcube_mikrolink_<id>}
 * schema owns its own {@code OUR_COMPANY} table with the legal name of that
 * tenant. The dropdown needs the full {id, nickname, name} list so the user
 * can pick which company schema to query.
 *
 * <p>Server-side numeric allowlist drives the schema list — schema names are
 * never derived from request input. The allowlist is the integer range
 * {@code [companyIdMin..companyIdMax]} ({@link #companyIdMin} /
 * {@link #companyIdMax}, defaulted to 1..43 — live MSSQL evidence: workcube
 * tenant has 43 company-only schemas).
 *
 * <p>Single round-trip {@code UNION ALL} across all enabled schemas, filtered
 * server-side by {@code sys.schemas} so missing schemas don't crash the
 * query. Caller is expected to layer Spring's {@link
 * org.springframework.cache.annotation.Cacheable} on top — see
 * {@link CompanyOptionsService}.
 *
 * <p>Activation: same conditional as the rest of the workcube package —
 * feature flag {@code report.mssql.enabled=true} → {@code
 * workcubeMssqlDataSource} bean → this repository auto-registers.
 */
@Repository
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class CompanyOptionsRepository {

    private static final Logger log = LoggerFactory.getLogger(CompanyOptionsRepository.class);

    private final JdbcTemplate jdbc;
    private final int companyIdMin;
    private final int companyIdMax;

    public CompanyOptionsRepository(
            @Qualifier("workcubeMssqlDataSource") DataSource dataSource,
            @Value("${report.workcube.company-id-min:1}") int companyIdMin,
            @Value("${report.workcube.company-id-max:43}") int companyIdMax) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.companyIdMin = companyIdMin;
        this.companyIdMax = companyIdMax;
    }

    /**
     * Returns every Workcube company the cluster knows about. Filtering by
     * caller authorization (super-admin vs. company-scoped user) is the
     * service layer's responsibility — this method intentionally returns the
     * full catalog so the cache stays per-cluster and cheap.
     */
    public List<CompanyOption> findAll() {
        // Build the schema id allowlist from server-side config (never from
        // request input). Each id maps to schema name workcube_mikrolink_<id>.
        List<Integer> ids = IntStream.rangeClosed(companyIdMin, companyIdMax)
                .boxed()
                .toList();
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        // Filter to schemas that actually exist on this server so the UNION
        // doesn't hit a missing schema and 500 the whole query.
        List<Integer> existing = filterExistingSchemas(ids);
        if (existing.isEmpty()) {
            log.warn("CompanyOptionsRepository: no workcube_mikrolink_* schemas exist (range {}..{})",
                    companyIdMin, companyIdMax);
            return Collections.emptyList();
        }

        String unionSql = existing.stream()
                .map(id -> String.format(
                        "SELECT %d AS id, NICKNAME AS nickname, COMPANY_NAME AS name "
                                + "FROM [workcube_mikrolink_%d].[OUR_COMPANY]",
                        id, id))
                .collect(Collectors.joining(" UNION ALL "));

        try {
            return jdbc.query(unionSql, (rs, rowNum) -> new CompanyOption(
                    rs.getInt("id"),
                    rs.getString("nickname"),
                    rs.getString("name")));
        } catch (DataAccessResourceFailureException ex) {
            // ADR-0005 degraded mode — MSSQL unreachable surfaces as 503 at
            // controller boundary; here we return empty list rather than
            // poison the catalog cache for the whole TTL.
            log.warn("CompanyOptionsRepository: MSSQL degraded — {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Returns the subset of {@code candidateIds} whose schema actually exists
     * in {@code sys.schemas}. {@code sys.schemas} is a system catalog, not
     * tenant data — read-only and safe to query without per-tenant guards.
     */
    private List<Integer> filterExistingSchemas(List<Integer> candidateIds) {
        if (candidateIds.isEmpty()) {
            return Collections.emptyList();
        }
        String inClause = candidateIds.stream()
                .map(id -> String.format("'workcube_mikrolink_%d'", id))
                .collect(Collectors.joining(","));
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT name FROM sys.schemas WHERE name IN (" + inClause + ")",
                    String.class);
            List<Integer> kept = new ArrayList<>();
            for (Integer id : candidateIds) {
                if (rows.contains("workcube_mikrolink_" + id)) {
                    kept.add(id);
                }
            }
            return kept;
        } catch (DataAccessResourceFailureException ex) {
            log.warn("CompanyOptionsRepository: sys.schemas probe failed — {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /** Plain DTO — id (numeric), nickname (short code), name (legal). */
    public record CompanyOption(int id, String nickname, String name) {}
}
