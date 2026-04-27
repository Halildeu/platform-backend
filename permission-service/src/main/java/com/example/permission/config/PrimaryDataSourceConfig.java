package com.example.permission.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Faz 21.3 PR-C: explicit primary datasource scoping for permission-service.
 *
 * <p>The default Spring Boot JPA auto-configuration would scan every package under
 * {@code com.example.permission} (including {@code dataaccess}) and bind every
 * entity to the primary persistence unit. With the secondary {@code reports_db}
 * datasource introduced for {@link com.example.permission.dataaccess.DataAccessScope},
 * we have to define both halves explicitly so Hibernate validates each entity
 * against the correct schema.
 *
 * <p>Primary scope (this config):
 * <ul>
 *   <li>repositories: {@code com.example.permission.repository}, {@code .outbox}</li>
 *   <li>entities: {@code com.example.permission.model}, {@code .outbox}</li>
 * </ul>
 * Secondary scope ({@link ReportsDbDataSourceConfig}): {@code .dataaccess}.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = {
                "com.example.permission.repository",
                "com.example.permission.outbox"
        },
        entityManagerFactoryRef = "primaryEntityManagerFactory",
        transactionManagerRef = "transactionManager"
)
public class PrimaryDataSourceConfig {

    @Primary
    @Bean(name = "primaryDataSourceProperties")
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean(name = "dataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource primaryDataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Primary
    @Bean(name = "primaryEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean primaryEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("dataSource") HikariDataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages(
                        "com.example.permission.model",
                        "com.example.permission.outbox"
                )
                .persistenceUnit("primary")
                .build();
    }

    @Primary
    @Bean(name = "transactionManager")
    public PlatformTransactionManager transactionManager(
            @Qualifier("primaryEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
