package com.example.budget.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TenantDatabaseScope {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public TenantDatabaseScope(
            JdbcTemplate jdbc,
            @Value("${budget.rls-session-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public void apply(String tenantId) {
        if (enabled) {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId);
        }
    }
}
