package com.example.schema.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
@EnableTransactionManagement
@EnableConfigurationProperties
public class MssqlConfig {

    /**
     * The Workcube MSSQL pool, declared explicitly and marked primary.
     *
     * <p>Until gitops#3594 this service relied on Spring Boot to auto-configure
     * its single {@code spring.datasource}. That auto-configuration is guarded by
     * {@code @ConditionalOnMissingBean(DataSource.class)}, so the moment
     * {@link OracleSourceConfig} declared a second pool the MSSQL one silently
     * stopped existing — and every {@code JdbcTemplate} in the context, this
     * class's included, was wired to the only pool left: Oracle. Measured live on
     * the first rollout: startup logs showed {@code schema-oracle-pool} and no
     * {@code schema-mssql-pool}, and the Workcube lane's {@code sys.schemas} read
     * failed with {@code ORA-00942: table or view does not exist} in 40ms.
     *
     * <p>Binding mirrors Boot's own Hikari configuration: connection settings
     * from {@code spring.datasource.*}, pool settings from
     * {@code spring.datasource.hikari.*}, so application.yml keeps working
     * unchanged. {@code @Primary} is what makes Boot's JdbcTemplate
     * auto-configuration (single-candidate) and by-type injection pick this
     * pool over the Oracle one.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties mssqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties mssqlDataSourceProperties) {
        HikariDataSource ds = mssqlDataSourceProperties
            .initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
        if (StringUtils.hasText(mssqlDataSourceProperties.getName())) {
            ds.setPoolName(mssqlDataSourceProperties.getName());
        }
        return ds;
    }

    /**
     * MSSQL system-catalog reads (notably {@code SchemaExtractService.extractTables})
     * run wide JOINs over {@code sys.*}. For large schemas — {@code workcube_mikrolink}
     * has 1509 tables / 26240 columns — the previously hard-coded 60s JDBC
     * {@code queryTimeout} was too short: the read timed out, 500'd, and collapsed
     * the whole snapshot. The timeout is now property-driven
     * ({@code schema.mssql.query-timeout-seconds}, env
     * {@code SCHEMA_MSSQL_QUERY_TIMEOUT_SECONDS}, default 60) so each environment
     * can size it to its schema.
     *
     * <p>Injected by type: with the MSSQL pool marked primary this is the MSSQL
     * pool even though an Oracle {@link DataSource} also exists in the context.
     */
    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(
            DataSource dataSource,
            @Value("${schema.mssql.query-timeout-seconds:60}") int queryTimeoutSeconds) {
        var template = new NamedParameterJdbcTemplate(dataSource);
        template.getJdbcTemplate().setQueryTimeout(queryTimeoutSeconds);
        return template;
    }
}
