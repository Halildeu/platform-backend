package com.example.budget.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RuntimeDatabaseRoleGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public RuntimeDatabaseRoleGuard(
            JdbcTemplate jdbc,
            @Value("${budget.database-role-guard-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        DatabaseRole role = jdbc.queryForObject("""
                SELECT current_user AS role_name, r.rolsuper, r.rolbypassrls
                  FROM pg_roles r
                 WHERE r.rolname = current_user
                """, (rs, rowNum) -> new DatabaseRole(
                rs.getString("role_name"),
                rs.getBoolean("rolsuper"),
                rs.getBoolean("rolbypassrls")));
        if (role == null || role.superuser() || role.bypassRls()) {
            throw new IllegalStateException(
                    "budget-service runtime database role must be NOSUPERUSER and NOBYPASSRLS");
        }
    }

    private record DatabaseRole(String name, boolean superuser, boolean bypassRls) {
    }
}
