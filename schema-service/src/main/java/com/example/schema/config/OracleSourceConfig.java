package com.example.schema.config;

import com.example.schema.catalog.CatalogReader;
import com.example.schema.catalog.OracleCatalogReader;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * A second, read-only catalog source: the IFS ERP Oracle database (gitops#3594).
 *
 * <p>Off unless {@code schema.sources.oracle.enabled=true}, so an environment
 * without the credentials or the network route starts exactly as before rather
 * than failing on a datasource it cannot reach. The connection details arrive as
 * environment variables rendered from Vault ({@code kv/platform/oracle-ifs}) —
 * never from this repository.
 *
 * <p>The pool is small and explicitly read-only. This is a reporting lane into a
 * production ERP: it must never hold writes open, and it must not be able to
 * exhaust the ERP's session budget if the snapshot builder is called repeatedly.
 */
@Configuration
@ConditionalOnProperty(value = "schema.sources.oracle.enabled", havingValue = "true")
public class OracleSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(OracleSourceConfig.class);

    @Value("${schema.sources.oracle.id:ifs}")
    private String sourceId;

    @Value("${schema.sources.oracle.jdbc-url}")
    private String jdbcUrl;

    @Value("${schema.sources.oracle.username}")
    private String username;

    @Value("${schema.sources.oracle.password}")
    private String password;

    @Value("${schema.sources.oracle.default-schema:IFSAPP}")
    private String defaultSchema;

    @Value("${schema.sources.oracle.pool-size:4}")
    private int poolSize;

    /**
     * The extractTables read spans the whole dictionary — 205,874 columns on the
     * measured IFS instance, 22 seconds end to end. The default 60s JDBC timeout
     * leaves usable headroom but not much, so it is configurable per environment.
     */
    @Value("${schema.sources.oracle.query-timeout-seconds:120}")
    private int queryTimeoutSeconds;

    @Bean(destroyMethod = "close")
    public HikariDataSource oracleSourceDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("oracle.jdbc.OracleDriver");
        ds.setPoolName("schema-oracle-pool");
        ds.setMaximumPoolSize(poolSize);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(15_000);
        ds.setMaxLifetime(600_000);
        ds.setReadOnly(true);
        // A dictionary read must never block startup: without this the pool fails
        // fast when the ERP is briefly unreachable and takes the whole service
        // down with it, even though every other source is healthy.
        ds.setInitializationFailTimeout(-1);
        log.info("Oracle catalog source '{}' configured (schema {}, pool {})",
            sourceId, defaultSchema, poolSize);
        return ds;
    }

    /**
     * Qualified by name on purpose. Two {@link HikariDataSource} beans live in
     * this context and the MSSQL one is primary; by-type injection here would
     * hand the Oracle reader the Workcube pool.
     */
    @Bean
    public CatalogReader oracleCatalogReader(
            @Qualifier("oracleSourceDataSource") HikariDataSource oracleSourceDataSource) {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(oracleSourceDataSource);
        jdbc.getJdbcTemplate().setQueryTimeout(queryTimeoutSeconds);
        return new OracleCatalogReader(sourceId, jdbc, defaultSchema);
    }
}
