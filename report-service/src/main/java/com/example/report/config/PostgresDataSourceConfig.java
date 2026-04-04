package com.example.report.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * PostgreSQL datasource for report metadata persistence (custom reports, alerts, schedules).
 * MSSQL remains the primary datasource for report data queries.
 */
@Configuration
public class PostgresDataSourceConfig {

    @Value("${report.postgres.url:jdbc:postgresql://localhost:5432/users}")
    private String url;

    @Value("${report.postgres.username:postgres}")
    private String username;

    @Value("${report.postgres.password:postgres}")
    private String password;

    @Bean("pgDataSource")
    @FlywayDataSource
    public DataSource pgDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName("report-pg-pool");
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(10_000);
        ds.setMaxLifetime(300_000);
        return ds;
    }

    @Bean("pgJdbc")
    public NamedParameterJdbcTemplate pgJdbcTemplate() {
        return new NamedParameterJdbcTemplate(pgDataSource());
    }
}
