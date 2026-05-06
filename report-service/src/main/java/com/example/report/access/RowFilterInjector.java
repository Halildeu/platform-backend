package com.example.report.access;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ReportDefinition;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * Builds the per-report RLS WHERE clause from the caller's authz scope.
 *
 * <p><b>Super-admin semantics</b> (Codex 019dfc41 iter-4 absorb):
 * super-admin <em>without</em> explicit scope still bypasses RLS, but
 * super-admin <em>with</em> explicit scope (e.g. populated by
 * {@link com.example.report.authz.CompanyHeaderScopeNarrower} from the
 * {@code X-Company-Id} picker header) is treated like any scoped user
 * for that scope axis. This keeps the legacy unrestricted-admin path
 * for callers that don't pass the picker header, while making the
 * picker actually do something for super-admin too.
 */
@Component
public class RowFilterInjector {

    public record RlsResult(String whereClause, MapSqlParameterSource params) {}

    public RlsResult buildRlsClause(ReportDefinition def, AuthzMeResponse authz) {
        if (authz == null) {
            return new RlsResult("1=0", new MapSqlParameterSource());
        }

        if (def.access() == null || def.access().rowFilter() == null) {
            return new RlsResult(null, null);
        }

        AccessConfig.RowFilter rowFilter = def.access().rowFilter();

        if (rowFilter.bypassPermission() != null && authz.hasPermission(rowFilter.bypassPermission())) {
            return new RlsResult(null, null);
        }

        String scopeType = rowFilter.scopeType();
        String column = rowFilter.column();

        if (scopeType == null || column == null) {
            return new RlsResult(null, null);
        }

        Set<String> allowedIds = authz.getScopeRefIds(scopeType);

        // Legacy unrestricted super-admin: only when there is no explicit
        // scope of this type on the authz object. With explicit scope —
        // typically the picker-narrowed singleton {COMPANY=<header>} —
        // we fall through and apply RLS so the picker actually filters.
        if (authz.isSuperAdmin() && allowedIds.isEmpty()) {
            return new RlsResult(null, null);
        }

        if (allowedIds.isEmpty()) {
            return new RlsResult("1=0", new MapSqlParameterSource());
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("_rlsIds", allowedIds);
        String clause = "[" + column + "] IN (:_rlsIds)";

        return new RlsResult(clause, params);
    }
}
