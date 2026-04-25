package com.example.report.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Workcube MSSQL secondary DataSource (ADR-0005 Dual DataSource Reporting).
 *
 * <p>Activation: feature flag {@code report.mssql.enabled=true} (default false).
 * Disabled by default until Workcube admin creates read-only DB user + Vault
 * seed (kv/platform/mssql-external) + ESO sync to K8s Secret completes.
 *
 * <p>Architecture (ADR-0005, Codex AGREE 019dc10b):
 * <ul>
 *   <li>PG = platform-native data (custom_reports, registry, alerts, schedules) — primary, write-able</li>
 *   <li>MSSQL = Workcube ERP read-only live (this config) — secondary, read-only enforced</li>
 *   <li>Network bridge: K8s Service+Endpoints (kustomize/base/host-services/workcube-mssql-svc.yaml)</li>
 *   <li>NetPol scoped: report-service + schema-service podSelector → 1433 egress</li>
 * </ul>
 *
 * <p>Runtime (ADR-0005 degraded mode):
 * <ul>
 *   <li>MSSQL unreachable → MSSQL-backed endpoints 503, PG-backed endpoints OK</li>
 *   <li>Health indicator separate (NOT in readiness — pod evict/restart döngüsü riski)</li>
 *   <li>Query timeout: 30s default, allowlist + parametric queries only</li>
 * </ul>
 *
 * <p>Beans (qualifier-based, no @Primary):
 * <ul>
 *   <li>{@code workcubeMssqlDataSource} (HikariDataSource, read-only=true)</li>
 *   <li>{@code workcubeMssqlJdbc} (NamedParameterJdbcTemplate)</li>
 *   <li>{@code workcubeMssqlPlainJdbc} (JdbcTemplate, common-auth lookup compat)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * &#64;Autowired
 * &#64;Qualifier("workcubeMssqlJdbc")
 * NamedParameterJdbcTemplate workcubeJdbc;
 *
 * List&lt;Map&lt;String,Object&gt;&gt; rows = workcubeJdbc.queryForList(
 *     "SELECT * FROM AllowlistedView WHERE id = :id",
 *     Map.of("id", id));
 * </pre>
 *
 * <p>NOT (eski kod): {@link MssqlDataSourceConfig} legacy, {@code @Primary} aggressive — bu yeni
 * config qualifier-based, eski path bozulmaz. Eski config gradual migrate veya retire ediilebilir.
 */
@Configuration
@ConditionalOnProperty(value = "report.mssql.enabled", havingValue = "true", matchIfMissing = false)
public class WorkcubeMssqlConfig {

    @Value("${report.mssql.jdbc-url:#{null}}")
    private String jdbcUrl;

    @Value("${report.mssql.username:#{null}}")
    private String username;

    @Value("${report.mssql.password:#{null}}")
    private String password;

    @Value("${report.mssql.pool.max-size:5}")
    private int maxPoolSize;

    @Value("${report.mssql.pool.connection-timeout-ms:10000}")
    private long connectionTimeoutMs;

    @Value("${report.mssql.pool.max-lifetime-ms:300000}")
    private long maxLifetimeMs;

    /**
     * Workcube MSSQL HikariDataSource — read-only enforced, dedicated pool.
     *
     * <p>Hikari read-only: connection level read-only mode (driver flag),
     * server-side prevents writes even if SQL contains DML/DDL.
     */
    @Bean(name = "workcubeMssqlDataSource", destroyMethod = "close")
    public DataSource workcubeMssqlDataSource() {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException(
                "report.mssql.jdbc-url not set (required when report.mssql.enabled=true). "
                + "Vault path: kv/platform/mssql-external (jdbc_url field). "
                + "ESO ExternalSecret: kustomize/base/apps/report-service/ops/externalsecret-mssql.yaml"
            );
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setReadOnly(true);  // ADR-0005: read-only enforced
        ds.setPoolName("workcube-mssql-readonly");
        ds.setMaximumPoolSize(maxPoolSize);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(connectionTimeoutMs);
        ds.setMaxLifetime(maxLifetimeMs);
        ds.setAutoCommit(true);
        return ds;
    }

    /**
     * NamedParameterJdbcTemplate for parametric queries (preferred — SQL injection guard).
     *
     * <p>Usage example:
     * <pre>workcubeJdbc.queryForList("SELECT * FROM AllowlistedView WHERE id = :id", Map.of("id", id));</pre>
     */
    @Bean("workcubeMssqlJdbc")
    public NamedParameterJdbcTemplate workcubeMssqlJdbc() {
        NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(workcubeMssqlDataSource());
        template.getJdbcTemplate().setQueryTimeout(30);  // 30s timeout
        template.getJdbcTemplate().setMaxRows(10000);     // row limit guard
        return template;
    }

    /**
     * Plain JdbcTemplate for common-auth/legacy compatibility.
     */
    @Bean("workcubeMssqlPlainJdbc")
    public JdbcTemplate workcubeMssqlPlainJdbc() {
        JdbcTemplate template = new JdbcTemplate(workcubeMssqlDataSource());
        template.setQueryTimeout(30);
        template.setMaxRows(10000);
        return template;
    }
}
