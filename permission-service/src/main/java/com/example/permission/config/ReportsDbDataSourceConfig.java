package com.example.permission.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Faz 21.3 PR-C: secondary {@code reports_db} datasource for the
 * {@code data_access.*} schema (V19/V20 immutable migrations in
 * platform-k8s-gitops).
 *
 * <p>Entity scope intentionally narrow: only
 * {@code com.example.permission.dataaccess}. Hibernate validates these entities
 * against the {@code reports_db} schema, never the primary permission DB.
 *
 * <p>Activation gate: {@code spring.datasource.reports-db.enabled} — an
 * explicit boolean sentinel (env: {@code REPORTS_DB_ENABLED}). The URL alone
 * is not enough as a gate, because {@code ${REPORTS_DB_URL:}} resolves to an
 * empty string when the env var is unset, and
 * {@code @ConditionalOnProperty(name=...url)} matches "present and non-false"
 * — which would silently activate the secondary DS in any k8s pod where the
 * env was forgotten. The explicit {@code enabled=true} requires a deliberate
 * opt-in. When false (local dev / unit test / un-wired prod), the bean graph
 * is skipped so the application boots without a {@code reports_db} reachable.
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.reports-db.enabled", havingValue = "true")
@EnableJpaRepositories(
        basePackages = "com.example.permission.dataaccess",
        entityManagerFactoryRef = "reportsDbEntityManagerFactory",
        transactionManagerRef = "reportsDbTransactionManager"
)
public class ReportsDbDataSourceConfig {

    @Bean(name = "reportsDbDataSourceProperties")
    @ConfigurationProperties("spring.datasource.reports-db")
    public DataSourceProperties reportsDbDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "reportsDbDataSource")
    @ConfigurationProperties("spring.datasource.reports-db.hikari")
    public HikariDataSource reportsDbDataSource(
            @Qualifier("reportsDbDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "reportsDbEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean reportsDbEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("reportsDbDataSource") HikariDataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages("com.example.permission.dataaccess")
                .persistenceUnit("reportsDb")
                .build();
    }

    @Bean(name = "reportsDbTransactionManager")
    public PlatformTransactionManager reportsDbTransactionManager(
            @Qualifier("reportsDbEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    /**
     * 2026-04-29: Lightweight JdbcTemplate for master data scope picker
     * read paths (workcube_mikrolink.our_company, pro_projects, branch,
     * department). Direct SQL avoids JPA entity overhead for read-only
     * dropdown lists. See {@code MasterDataController} +
     * {@code MasterDataService}.
     */
    @Bean(name = "reportsDbJdbcTemplate")
    public JdbcTemplate reportsDbJdbcTemplate(
            @Qualifier("reportsDbDataSource") HikariDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
