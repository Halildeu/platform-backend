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
 * <p>Activation gate: {@code spring.datasource.reports-db.url}. When unset
 * (local dev / unit test), the bean graph is skipped so the application boots
 * without a {@code reports_db} reachable. Production binding happens in
 * {@code application-k8s.yml} via {@code REPORTS_DB_URL/USERNAME/PASSWORD}.
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.reports-db.url")
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
}
