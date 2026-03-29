package com.example.commonauth.scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility to set PostgreSQL session variables for RLS policies.
 * Called by each service's interceptor to inject scope into the DB connection.
 *
 * Uses SET LOCAL (transaction-scoped) so settings auto-clear on COMMIT.
 */
public final class RlsScopeHelper {

    private static final Logger log = LoggerFactory.getLogger(RlsScopeHelper.class);

    private RlsScopeHelper() {
    }

    /**
     * Set the RLS scope variables on the given JDBC connection.
     * Uses SET LOCAL so the setting is transaction-scoped.
     */
    public static void applyScope(Connection connection, ScopeContext ctx) throws SQLException {
        if (ctx == null) {
            return;
        }

        if (ctx.superAdmin()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT set_config('app.scope.bypass_rls', 'true', true)")) {
                ps.execute();
            }
            log.debug("RLS bypass set for superAdmin user {}", ctx.userId());
            return;
        }

        if (!ctx.allowedCompanyIds().isEmpty()) {
            String ids = ctx.allowedCompanyIds().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT set_config('app.scope.company_ids', ?, true)")) {
                ps.setString(1, ids);
                ps.execute();
            }
            log.debug("RLS scope set: company_ids={} for user {}", ids, ctx.userId());
        }
    }
}
