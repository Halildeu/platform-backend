package com.example.schema.config;

import com.example.schema.catalog.CatalogReader;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins which pool each lane talks to once two are configured (gitops#3594).
 *
 * <p>The failure this guards against did not throw at startup. With the Oracle
 * pool declared, Spring Boot's {@code @ConditionalOnMissingBean(DataSource)}
 * auto-configuration quietly stopped creating the MSSQL pool, and every
 * template in the context — the Workcube reader's and the yearly-discovery
 * {@link JdbcTemplate} alike — was wired to the only pool left. The service
 * came up healthy, the Oracle lane worked, and the Workcube lane answered
 * {@code ORA-00942} from an Oracle database it should never have reached.
 * Pools are lazy and {@code initialization-fail-timeout=-1}, so this asserts
 * wiring without opening a connection.
 */
class DataSourceWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class))
        .withUserConfiguration(MssqlConfig.class, OracleSourceConfig.class)
        .withPropertyValues(
            "spring.datasource.url=jdbc:sqlserver://mssql.invalid:1433;databaseName=workcube_mikrolink",
            "spring.datasource.username=u",
            "spring.datasource.password=p",
            "spring.datasource.driver-class-name=com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "spring.datasource.hikari.pool-name=schema-mssql-pool",
            "spring.datasource.hikari.initialization-fail-timeout=-1",
            "schema.sources.oracle.enabled=true",
            "schema.sources.oracle.jdbc-url=jdbc:oracle:thin:@//oracle.invalid:1521/PROD",
            "schema.sources.oracle.username=u",
            "schema.sources.oracle.password=p");

    @Test
    void mssqlLaneKeepsItsOwnPoolWhenOracleIsEnabled() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(HikariDataSource.class)).hasSize(2);

            HikariDataSource primary = (HikariDataSource) ctx.getBean(NamedParameterJdbcTemplate.class)
                .getJdbcTemplate().getDataSource();
            assertThat(primary.getJdbcUrl()).startsWith("jdbc:sqlserver:");
            assertThat(primary.getPoolName()).isEqualTo("schema-mssql-pool");

            // The yearly-partition discovery injects a plain JdbcTemplate; it must
            // land on MSSQL too, not on whichever pool happened to survive.
            HikariDataSource plain = (HikariDataSource) ctx.getBean(JdbcTemplate.class).getDataSource();
            assertThat(plain.getJdbcUrl()).startsWith("jdbc:sqlserver:");

            HikariDataSource oracle = ctx.getBean("oracleSourceDataSource", HikariDataSource.class);
            assertThat(oracle.getJdbcUrl()).startsWith("jdbc:oracle:");
            assertThat(oracle).isNotSameAs(primary);
        });
    }

    @Test
    void oraclePoolKeepsItsFetchSizeAcrossLongColumns() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            HikariDataSource oracle = ctx.getBean("oracleSourceDataSource", HikariDataSource.class);
            // Without this the driver fetches LONG selects one row per round trip:
            // 138s for 10,885 view definitions, measured live.
            assertThat(oracle.getDataSourceProperties())
                .containsEntry("oracle.jdbc.useFetchSizeWithLongColumn", "true");
        });
    }

    @Test
    void oracleReaderIsRegisteredAlongsideTheMssqlPool() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            CatalogReader oracle = ctx.getBean("oracleCatalogReader", CatalogReader.class);
            assertThat(oracle.engine()).isEqualTo("oracle");
            assertThat(oracle.defaultSchema()).isEqualTo("IFSAPP");
        });
    }

    @Test
    void oracleDisabledLeavesExactlyTheMssqlPool() {
        runner.withPropertyValues("schema.sources.oracle.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(HikariDataSource.class)).hasSize(1);
            assertThat(ctx).doesNotHaveBean("oracleCatalogReader");
            HikariDataSource only = (HikariDataSource) ctx.getBean(NamedParameterJdbcTemplate.class)
                .getJdbcTemplate().getDataSource();
            assertThat(only.getJdbcUrl()).startsWith("jdbc:sqlserver:");
        });
    }
}
