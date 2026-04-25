package com.example.report.workcube;

import java.sql.Connection;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Workcube MSSQL custom health indicator (ADR-0005 Dual DataSource Reporting).
 *
 * <p>Bean adı: {@code workcubeMssql} → Spring Actuator endpoint
 * {@code /actuator/health/workcubeMssql} altında görünür.
 *
 * <p><b>ÖNEMLİ:</b> Bu indicator readiness probe'a DAHİL DEĞİL.
 * Codex AGREE 019dc10b: "MSSQL down olduğunda pod evict/restart döngüsüne girmemeli;
 * PG-backed endpoint'ler ayakta kalmalı." K8s readinessProbe yalnız
 * {@code /actuator/health/readiness} okuyor; MSSQL bu group'a eklenmedi.
 *
 * <p>Custom indicator amacı:
 * <ul>
 *   <li>Operasyonel görünürlük: {@code /actuator/health} altında DOWN/UP gözükür</li>
 *   <li>Prometheus metrik (otomatik)</li>
 *   <li>UI/dashboard için son kanıt (degraded mode banner vs)</li>
 * </ul>
 *
 * <p>Probe stratejisi: Connection.isValid(2s timeout) — light, query yok.
 */
@Component("workcubeMssql")
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class WorkcubeMssqlHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(WorkcubeMssqlHealthIndicator.class);
    private static final int VALIDATE_TIMEOUT_SEC = 2;

    private final DataSource dataSource;

    public WorkcubeMssqlHealthIndicator(@Qualifier("workcubeMssqlDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection c = dataSource.getConnection()) {
            boolean valid = c.isValid(VALIDATE_TIMEOUT_SEC);
            if (valid) {
                return Health.up()
                    .withDetail("pool", "workcube-mssql-readonly")
                    .withDetail("readOnly", c.isReadOnly())
                    .build();
            }
            return Health.down()
                .withDetail("reason", "connection.isValid=false")
                .build();
        } catch (Exception ex) {
            log.debug("Workcube MSSQL health probe failed: {}", ex.getMessage());
            return Health.down(ex)
                .withDetail("pool", "workcube-mssql-readonly")
                .build();
        }
    }
}
